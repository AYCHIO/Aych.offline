package network.thunder.core.communication.processor.implementations;

import network.thunder.core.communication.Message;
import network.thunder.core.communication.objects.lightning.subobjects.ChannelStatus;
import network.thunder.core.communication.objects.messages.MessageExecutor;
import network.thunder.core.communication.objects.messages.impl.message.gossip.objects.ChannelStatusObject;
import network.thunder.core.communication.objects.messages.impl.message.gossip.objects.PubkeyChannelObject;
import network.thunder.core.communication.objects.messages.impl.message.lightningestablish.LNEstablishAMessage;
import network.thunder.core.communication.objects.messages.impl.message.lightningestablish.LNEstablishBMessage;
import network.thunder.core.communication.objects.messages.impl.message.lightningestablish.LNEstablishCMessage;
import network.thunder.core.communication.objects.messages.impl.message.lightningestablish.LNEstablishDMessage;
import network.thunder.core.communication.objects.messages.interfaces.factories.ContextFactory;
import network.thunder.core.communication.objects.messages.interfaces.factories.LNEstablishMessageFactory;
import network.thunder.core.communication.objects.messages.interfaces.helper.LNEventHelper;
import network.thunder.core.communication.objects.messages.interfaces.helper.WalletHelper;
import network.thunder.core.communication.objects.messages.interfaces.message.lightningestablish.LNEstablish;
import network.thunder.core.communication.processor.implementations.gossip.BroadcastHelper;
import network.thunder.core.communication.processor.interfaces.LNEstablishProcessor;
import network.thunder.core.database.DBHandler;
import network.thunder.core.database.objects.Channel;
import network.thunder.core.etc.Tools;
import network.thunder.core.mesh.NodeClient;
import network.thunder.core.mesh.NodeServer;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.TransactionSignature;

/**
 * Created by matsjerratsch on 03/12/2015.
 */
public class LNEstablishProcessorImpl extends LNEstablishProcessor {
    public static final double PERCENTAGE_OF_FUNDS_PER_CHANNEL = 0.1;

    WalletHelper walletHelper;
    LNEstablishMessageFactory messageFactory;
    BroadcastHelper broadcastHelper;
    LNEventHelper eventHelper;
    DBHandler dbHandler;
    NodeClient node;
    NodeServer nodeServer;

    MessageExecutor messageExecutor;

    public Channel channel;
    int status = 0;

    public LNEstablishProcessorImpl (ContextFactory contextFactory, DBHandler dbHandler, NodeClient node) {
        this.walletHelper = contextFactory.getWalletHelper();
        this.messageFactory = contextFactory.getLNEstablishMessageFactory();
        this.broadcastHelper = contextFactory.getBroadcastHelper();
        this.eventHelper = contextFactory.getEventHelper();
        this.dbHandler = dbHandler;
        this.node = node;
        this.nodeServer = contextFactory.getServerSettings();
    }

    @Override
    public void onInboundMessage (Message message) {
        consumeMessage(message);
    }

    @Override
    public boolean consumesInboundMessage (Object object) {
        return object instanceof LNEstablish;
    }

    @Override
    public boolean consumesOutboundMessage (Object object) {
        return false;
    }

    @Override
    public void onLayerActive (MessageExecutor messageExecutor) {
        this.messageExecutor = messageExecutor;
        if (!node.isServer) {
            sendEstablishMessageA();
        }
    }

    private void consumeMessage (Message message) {
        if (message instanceof LNEstablishAMessage) {
            processMessageA(message);
        } else if (message instanceof LNEstablishBMessage) {
            processMessageB(message);
        } else if (message instanceof LNEstablishCMessage) {
            processMessageC(message);
        } else if (message instanceof LNEstablishDMessage) {
            processMessageD(message);
        } else {
            throw new UnsupportedOperationException("Don't know this LNEstablish Message: " + message);
        }
    }

    private void processMessageA (Message message) {
        LNEstablishAMessage m = (LNEstablishAMessage) message;
        prepareNewChannel();

        channel.setInitialAmountServer(m.clientAmount);
        channel.setAmountServer(m.clientAmount);
        channel.setInitialAmountClient(m.serverAmount);
        channel.setAmountClient(m.serverAmount);

        channel.setKeyClient(ECKey.fromPublicOnly(m.pubKeyEscape));
        channel.setKeyClientA(ECKey.fromPublicOnly(m.pubKeyFastEscape));
        channel.setAnchorSecretHashClient(m.secretHashFastEscape);
        channel.setAnchorRevocationHashClient(m.revocationHash);

        sendEstablishMessageB();
    }

    private void processMessageB (Message message) {
        LNEstablishBMessage m = (LNEstablishBMessage) message;

        channel.setKeyClient(ECKey.fromPublicOnly(m.pubKeyEscape));
        channel.setKeyClientA(ECKey.fromPublicOnly(m.pubKeyFastEscape));

        channel.setAnchorSecretHashClient(m.secretHashFastEscape);
        channel.setAnchorRevocationHashClient(m.revocationHash);
        channel.setAmountClient(m.serverAmount);
        channel.setInitialAmountClient(m.serverAmount);

        channel.setAnchorTxHashClient(Sha256Hash.wrap(m.anchorHash));

        sendEstablishMessageC();
    }

    private void processMessageC (Message message) {

        LNEstablishCMessage m = (LNEstablishCMessage) message;

        channel.setAnchorTxHashClient(Sha256Hash.wrap(m.anchorHash));
        channel.setEscapeTxSig(TransactionSignature.decodeFromBitcoin(m.signatureEscape, true));
        channel.setFastEscapeTxSig(TransactionSignature.decodeFromBitcoin(m.signatureFastEscape, true));

        if (!channel.verifyEscapeSignatures()) {
            throw new RuntimeException("Signature does not match..");
        }

        sendEstablishMessageD();
        onChannelEstablished();

    }

    private void processMessageD (Message message) {
        LNEstablishDMessage m = (LNEstablishDMessage) message;

        channel.setEscapeTxSig(TransactionSignature.decodeFromBitcoin(m.signatureEscape, true));
        channel.setFastEscapeTxSig(TransactionSignature.decodeFromBitcoin(m.signatureFastEscape, true));

        PubkeyChannelObject channelObject = PubkeyChannelObject.getRandomObject();
        broadcastHelper.broadcastNewObject(channelObject);

        if (!channel.verifyEscapeSignatures()) {
            throw new RuntimeException("Signature does not match..");
        }
        //TODO: Everything needed has been exchanged. We can now open the channel / wait to see the other channel on the blockchain.
        //          We need a WatcherClass on the BlockChain for that, to wait till the anchors are sufficiently deep in the blockchain.
        onChannelEstablished();
    }

    private void onChannelEstablished () {
        channel.channelStatus = new ChannelStatus();
        channel.channelStatus.amountClient = channel.amountClient;
        channel.channelStatus.amountServer = channel.amountServer;
        channel.channelStatus.applyConfiguration(nodeServer.configuration);
        dbHandler.saveChannel(channel);

        PubkeyChannelObject channelObject = PubkeyChannelObject.getRandomObject();

        ChannelStatusObject statusObject = new ChannelStatusObject();
        statusObject.pubkeyA = nodeServer.pubKeyServer.getPubKey();
        statusObject.pubkeyB = node.pubKeyClient.getPubKey();

        broadcastHelper.broadcastNewObject(channelObject);
        broadcastHelper.broadcastNewObject(statusObject);

        eventHelper.onChannelOpened(channel);
        messageExecutor.sendNextLayerActive();
    }

    private void sendEstablishMessageA () {
        prepareNewChannel();

        Message message = messageFactory.getEstablishMessageA(channel);
        messageExecutor.sendMessageUpwards(message);

        status = 2;
    }

    private void sendEstablishMessageB () {

        Transaction anchor = channel.getAnchorTransactionServer(walletHelper);

        Message message = messageFactory.getEstablishMessageB(channel, anchor);
        messageExecutor.sendMessageUpwards(message);

        status = 3;
    }

    private void sendEstablishMessageC () {
        Transaction anchor = channel.getAnchorTransactionServer(walletHelper);

        Transaction escape = channel.getEscapeTransactionClient();
        Transaction fastEscape = channel.getFastEscapeTransactionClient();

        TransactionSignature escapeSig = Tools.getSignature(escape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel.getKeyServerA());
        TransactionSignature fastEscapeSig = Tools.getSignature(fastEscape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel
                .getKeyServerA());

        Message message = messageFactory.getEstablishMessageC(anchor, escapeSig, fastEscapeSig);
        messageExecutor.sendMessageUpwards(message);

        status = 4;
    }

    private void sendEstablishMessageD () {

        Transaction escape = channel.getEscapeTransactionClient();
        Transaction fastEscape = channel.getFastEscapeTransactionClient();

        TransactionSignature escapeSig = Tools.getSignature(escape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel.getKeyServerA());
        TransactionSignature fastEscapeSig = Tools.getSignature(fastEscape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel
                .getKeyServerA());

        Message message = messageFactory.getEstablishMessageD(escapeSig, fastEscapeSig);
        messageExecutor.sendMessageUpwards(message);

        status = 5;
    }

    private void prepareNewChannel () {
        channel = new Channel();
        channel.nodeId = node.pubKeyClient.getPubKey();

        channel.setInitialAmountServer(getAmountForNewChannel());
        channel.setAmountServer(getAmountForNewChannel());

        channel.setInitialAmountClient(getAmountForNewChannel());
        channel.setAmountClient(getAmountForNewChannel());

        //TODO: Change base key selection method (maybe completely random?)
        channel.setKeyServer(ECKey.fromPrivate(Tools.hashSha(nodeServer.pubKeyServer.getPrivKeyBytes(), 2)));
        channel.setKeyServerA(ECKey.fromPrivate(Tools.hashSha(nodeServer.pubKeyServer.getPrivKeyBytes(), 4)));
        channel.setMasterPrivateKeyServer(Tools.hashSha(nodeServer.pubKeyServer.getPrivKeyBytes(), 6));
        byte[] secretFE = Tools.hashSecret(Tools.hashSha(nodeServer.pubKeyServer.getPrivKeyBytes(), 8));
        byte[] revocation = Tools.hashSecret(Tools.hashSha(nodeServer.pubKeyServer.getPrivKeyBytes(), 10));
        channel.setAnchorSecretServer(secretFE);
        channel.setAnchorSecretHashServer(Tools.hashSecret(secretFE));
        channel.setAnchorRevocationServer(revocation);
        channel.setAnchorRevocationHashServer(Tools.hashSecret(revocation));
        channel.setServerChainDepth(1000);
        channel.setServerChainChild(0);
        channel.setIsReady(false);

        channel.channelStatus.applyConfiguration(nodeServer.configuration);

        status = 1;

    }

    private long getAmountForNewChannel () {
        return (long) (walletHelper.getSpendableAmount() * PERCENTAGE_OF_FUNDS_PER_CHANNEL);
    }

}
