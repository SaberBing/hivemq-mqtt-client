/*
 * Copyright 2018 The MQTT Bee project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.mqttbee.mqtt.handler.publish.incoming;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mqttbee.annotations.CallByThread;
import org.mqttbee.api.mqtt.MqttClientState;
import org.mqttbee.api.mqtt.mqtt5.advanced.qos1.Mqtt5IncomingQos1Interceptor;
import org.mqttbee.api.mqtt.mqtt5.advanced.qos2.Mqtt5IncomingQos2Interceptor;
import org.mqttbee.api.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode;
import org.mqttbee.api.mqtt.mqtt5.message.publish.pubcomp.Mqtt5PubCompReasonCode;
import org.mqttbee.mqtt.MqttClientConfig;
import org.mqttbee.mqtt.MqttClientConnectionConfig;
import org.mqttbee.mqtt.advanced.MqttAdvancedClientData;
import org.mqttbee.mqtt.handler.disconnect.MqttDisconnectEvent;
import org.mqttbee.mqtt.handler.disconnect.MqttDisconnectUtil;
import org.mqttbee.mqtt.ioc.ClientScope;
import org.mqttbee.mqtt.message.publish.MqttStatefulPublish;
import org.mqttbee.mqtt.message.publish.puback.MqttPubAck;
import org.mqttbee.mqtt.message.publish.puback.MqttPubAckBuilder;
import org.mqttbee.mqtt.message.publish.pubcomp.MqttPubComp;
import org.mqttbee.mqtt.message.publish.pubcomp.MqttPubCompBuilder;
import org.mqttbee.mqtt.message.publish.pubrec.MqttPubRec;
import org.mqttbee.mqtt.message.publish.pubrec.MqttPubRecBuilder;
import org.mqttbee.mqtt.message.publish.pubrel.MqttPubRel;
import org.mqttbee.util.UnsignedDataTypes;
import org.mqttbee.util.collections.IntMap;
import org.mqttbee.util.netty.ContextFuture;
import org.mqttbee.util.netty.DefaultContextPromise;

import javax.inject.Inject;

import static org.mqttbee.api.mqtt.datatypes.MqttQos.*;

/**
 * @author Silvio Giebl
 */
@ClientScope
public class MqttIncomingQosHandler extends ChannelInboundHandlerAdapter implements ContextFuture.Listener<MqttPubAck> {

    public static final @NotNull String NAME = "qos.incoming";

    private final @NotNull MqttClientConfig clientConfig;
    private final @NotNull MqttIncomingPublishFlows incomingPublishFlows;
    private final @NotNull MqttIncomingPublishService incomingPublishService;

    private final @NotNull IntMap<Object> messages = IntMap.range(1, UnsignedDataTypes.UNSIGNED_SHORT_MAX_VALUE);
    // contains AT_LEAST_ONCE, EXACTLY_ONCE, MqttPubAck or MqttPubRec

    private @Nullable ChannelHandlerContext ctx;
    private int receiveMaximum;

    @Inject
    MqttIncomingQosHandler(
            final @NotNull MqttClientConfig clientConfig,
            final @NotNull MqttIncomingPublishFlows incomingPublishFlows) {

        this.clientConfig = clientConfig;
        this.incomingPublishFlows = incomingPublishFlows;
        incomingPublishService = new MqttIncomingPublishService(this);
    }

    @Override
    public void handlerAdded(final @NotNull ChannelHandlerContext ctx) {
        this.ctx = ctx;

        final MqttClientConnectionConfig clientConnectionConfig = clientConfig.getRawClientConnectionConfig();
        assert clientConnectionConfig != null;

        receiveMaximum = clientConnectionConfig.getReceiveMaximum();
    }

    @Override
    public void channelRead(final @NotNull ChannelHandlerContext ctx, final @NotNull Object msg) {
        if (msg instanceof MqttStatefulPublish) {
            readPublish(ctx, (MqttStatefulPublish) msg);
        } else if (msg instanceof MqttPubRel) {
            readPubRel(ctx, (MqttPubRel) msg);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void readPublish(final @NotNull ChannelHandlerContext ctx, final @NotNull MqttStatefulPublish publish) {
        switch (publish.stateless().getQos()) {
            case AT_MOST_ONCE:
                readPublishQos0(publish);
                break;
            case AT_LEAST_ONCE:
                readPublishQos1(ctx, publish);
                break;
            case EXACTLY_ONCE:
                readPublishQos2(ctx, publish);
                break;
        }
    }

    private void readPublishQos0(final @NotNull MqttStatefulPublish publish) {
        incomingPublishService.onPublish(publish, receiveMaximum); // TODO own queue for QoS 0
    }

    private void readPublishQos1(final @NotNull ChannelHandlerContext ctx, final @NotNull MqttStatefulPublish publish) {
        final Object previousMessage = messages.put(publish.getPacketIdentifier(), AT_LEAST_ONCE);
        if (previousMessage == null) { // new message
            readNewPublishQos1Or2(ctx, publish);
        } else if (previousMessage == AT_LEAST_ONCE) { // resent message
            checkDupFlagSet(ctx, publish, previousMessage);
        } else if (previousMessage instanceof MqttPubAck) { // resent message and already acknowledged
            if (checkDupFlagSet(ctx, publish, previousMessage)) {
                writePubAck(ctx, (MqttPubAck) previousMessage);
            }
        } else { // MqttQos.EXACTLY_ONCE or MqttPubRec
            messages.put(publish.getPacketIdentifier(), previousMessage); // revert
            MqttDisconnectUtil.disconnect(ctx.channel(), Mqtt5DisconnectReasonCode.PROTOCOL_ERROR,
                    "QoS 1 PUBLISH must not be received with the same packet identifier as a QoS 2 PUBLISH");
        }
    }

    private void readPublishQos2(final @NotNull ChannelHandlerContext ctx, final @NotNull MqttStatefulPublish publish) {
        final Object previousMessage = messages.put(publish.getPacketIdentifier(), EXACTLY_ONCE);
        if (previousMessage == null) { // new message
            readNewPublishQos1Or2(ctx, publish);
        } else if (previousMessage == EXACTLY_ONCE) { // resent message
            checkDupFlagSet(ctx, publish, previousMessage);
        } else if (previousMessage instanceof MqttPubRec) { // resent message and already acknowledged
            if (checkDupFlagSet(ctx, publish, previousMessage)) {
                writePubRec(ctx, (MqttPubRec) previousMessage);
            }
        } else { // MqttQos.AT_LEAST_ONCE or MqttPubAck
            messages.put(publish.getPacketIdentifier(), previousMessage); // revert
            MqttDisconnectUtil.disconnect(ctx.channel(), Mqtt5DisconnectReasonCode.PROTOCOL_ERROR,
                    "QoS 2 PUBLISH must not be received with the same packet identifier as a QoS 1 PUBLISH");
        }
    }

    private void readNewPublishQos1Or2(
            final @NotNull ChannelHandlerContext ctx, final @NotNull MqttStatefulPublish publish) {

        if (!incomingPublishService.onPublish(publish, receiveMaximum)) {
            MqttDisconnectUtil.disconnect(ctx.channel(), Mqtt5DisconnectReasonCode.RECEIVE_MAXIMUM_EXCEEDED,
                    "Received more QoS 1 and/or 2 PUBLISHes than allowed by Receive Maximum");
        }
    }

    private boolean checkDupFlagSet(
            final @NotNull ChannelHandlerContext ctx, final @NotNull MqttStatefulPublish publish,
            final @NotNull Object previousMessage) {

        if (!publish.isDup()) {
            messages.put(publish.getPacketIdentifier(), previousMessage); // revert
            MqttDisconnectUtil.disconnect(ctx.channel(), Mqtt5DisconnectReasonCode.PROTOCOL_ERROR,
                    "DUP flag must be set for a resent QoS " + publish.stateless().getQos().getCode() + " PUBLISH");
            return false;
        }
        return true;
    }

    @CallByThread("Netty EventLoop")
    void ack(final @NotNull MqttStatefulPublish publish) {
        if (publish.stateless().getQos() == AT_MOST_ONCE) { // TODO remove if own queue for QoS 0
            return;
        }
        if (publish.stateless().getQos() == AT_LEAST_ONCE) {
            final MqttPubAck pubAck = buildPubAck(new MqttPubAckBuilder(publish));
            messages.put(publish.getPacketIdentifier(), pubAck);
            if (ctx != null) {
                writePubAck(ctx, pubAck);
            }
        } else { // EXACTLY_ONCE
            final MqttPubRec pubRec = buildPubRec(new MqttPubRecBuilder(publish));
            messages.put(publish.getPacketIdentifier(), pubRec);
            if (ctx != null) {
                writePubRec(ctx, pubRec);
            }
        }
    }

    private void writePubAck(final @NotNull ChannelHandlerContext ctx, final @NotNull MqttPubAck pubAck) {
        ctx.writeAndFlush(pubAck, new DefaultContextPromise<>(ctx.channel(), pubAck)).addListener(this);
    }

    @Override
    public void operationComplete(final @NotNull ContextFuture<MqttPubAck> future) {
        if (future.isSuccess()) {
            messages.remove(future.getContext().getPacketIdentifier());
        }
    }

    private void writePubRec(final @NotNull ChannelHandlerContext ctx, final @NotNull MqttPubRec pubRec) {
        ctx.writeAndFlush(pubRec, ctx.voidPromise());
    }

    private void readPubRel(final @NotNull ChannelHandlerContext ctx, final @NotNull MqttPubRel pubRel) {
        final Object previousMessage = messages.remove(pubRel.getPacketIdentifier());
        if (previousMessage instanceof MqttPubRec) { // normal case
            writePubComp(ctx, buildPubComp(new MqttPubCompBuilder(pubRel)));
        } else if (previousMessage == null) { // may be resent
            writePubComp(
                    ctx, buildPubComp(new MqttPubCompBuilder(pubRel).reasonCode(
                            Mqtt5PubCompReasonCode.PACKET_IDENTIFIER_NOT_FOUND)));
        } else if (previousMessage == EXACTLY_ONCE) { // PubRec not sent yet
            messages.put(pubRel.getPacketIdentifier(), previousMessage); // revert
            MqttDisconnectUtil.disconnect(ctx.channel(), Mqtt5DisconnectReasonCode.PROTOCOL_ERROR,
                    "PUBREL must not be received with the same packet identifier as a QoS 2 PUBLISH when no PUBREC has been sent yet");
        } else { // MqttQos.AT_LEAST_ONCE or MqttPubAck
            messages.put(pubRel.getPacketIdentifier(), previousMessage); // revert
            MqttDisconnectUtil.disconnect(ctx.channel(), Mqtt5DisconnectReasonCode.PROTOCOL_ERROR,
                    "PUBREL must not be received with the same packet identifier as a QoS 1 PUBLISH");
        }
    }

    private void writePubComp(final @NotNull ChannelHandlerContext ctx, final @NotNull MqttPubComp pubComp) {
        ctx.writeAndFlush(pubComp, ctx.voidPromise());
    }

    @Override
    public void userEventTriggered(final @NotNull ChannelHandlerContext ctx, final @NotNull Object evt) {
        if (evt instanceof MqttDisconnectEvent) {
            handleDisconnectEvent((MqttDisconnectEvent) evt);
        }
        ctx.fireUserEventTriggered(evt);
    }

    private void handleDisconnectEvent(final @NotNull MqttDisconnectEvent disconnectEvent) {
        ctx = null;

        if (clientConfig.getState() == MqttClientState.DISCONNECTED) {
            incomingPublishFlows.clear(disconnectEvent.getCause());
        }
    }

    private @NotNull MqttPubAck buildPubAck(final @NotNull MqttPubAckBuilder pubAckBuilder) {
        final MqttAdvancedClientData advanced = clientConfig.getRawAdvancedClientData();
        if (advanced != null) {
            final Mqtt5IncomingQos1Interceptor interceptor = advanced.getIncomingQos1Interceptor();
            if (interceptor != null) {
                interceptor.onPublish(clientConfig, pubAckBuilder.getPublish().stateless(), pubAckBuilder);
            }
        }
        return pubAckBuilder.build();
    }

    private @NotNull MqttPubRec buildPubRec(final @NotNull MqttPubRecBuilder pubRecBuilder) {
        final MqttAdvancedClientData advanced = clientConfig.getRawAdvancedClientData();
        if (advanced != null) {
            final Mqtt5IncomingQos2Interceptor interceptor = advanced.getIncomingQos2Interceptor();
            if (interceptor != null) {
                interceptor.onPublish(clientConfig, pubRecBuilder.getPublish().stateless(), pubRecBuilder);
            }
        }
        return pubRecBuilder.build();
    }

    private @NotNull MqttPubComp buildPubComp(final @NotNull MqttPubCompBuilder pubCompBuilder) {
        final MqttAdvancedClientData advanced = clientConfig.getRawAdvancedClientData();
        if (advanced != null) {
            final Mqtt5IncomingQos2Interceptor interceptor = advanced.getIncomingQos2Interceptor();
            if (interceptor != null) {
                interceptor.onPubRel(clientConfig, pubCompBuilder.getPubRel(), pubCompBuilder);
            }
        }
        return pubCompBuilder.build();
    }

    @NotNull MqttClientConfig getClientConfig() {
        return clientConfig;
    }

    @NotNull MqttIncomingPublishFlows getIncomingPublishFlows() {
        return incomingPublishFlows;
    }

    @NotNull MqttIncomingPublishService getIncomingPublishService() {
        return incomingPublishService;
    }

    @Override
    public boolean isSharable() {
        return ctx == null;
    }
}
