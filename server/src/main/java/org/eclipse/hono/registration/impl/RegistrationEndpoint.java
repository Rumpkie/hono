/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.registration.impl;

import static io.vertx.proton.ProtonHelper.condition;
import static org.eclipse.hono.registration.RegistrationConstants.APP_PROPERTY_CORRELATION_ID;
import static org.eclipse.hono.registration.RegistrationConstants.EVENT_BUS_ADDRESS_REGISTRATION_IN;
import static org.eclipse.hono.util.MessageHelper.encodeIdToJson;
import static org.eclipse.hono.util.MessageHelper.getLinkName;

import java.util.Objects;

import org.apache.qpid.proton.amqp.transport.AmqpError;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.registration.RegistrationConstants;
import org.eclipse.hono.registration.RegistrationMessageFilter;
import org.eclipse.hono.server.BaseEndpoint;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.ResourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;

/**
 * A Hono {@code Endpoint} for managing devices.
 */
public final class RegistrationEndpoint extends BaseEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationEndpoint.class);

    public RegistrationEndpoint(final Vertx vertx, final boolean singleTenant) {
        super(Objects.requireNonNull(vertx), singleTenant, 0);
    }

    public RegistrationEndpoint(final Vertx vertx, final boolean singleTenant, final int instanceId) {
        super(Objects.requireNonNull(vertx), singleTenant, instanceId);
    }

    @Override
    public String getName() {
        return RegistrationConstants.REGISTRATION_ENDPOINT;
    }

    @Override
    public void onLinkAttach(final ProtonReceiver receiver, final ResourceIdentifier targetAddress) {
        if (ProtonQoS.AT_MOST_ONCE.equals(receiver.getRemoteQoS())) {
            LOG.debug("client wants to use AT MOST ONCE delivery mode for registration endpoint, this is not supported.");
            receiver.setCondition(condition(AmqpError.PRECONDITION_FAILED.toString(),
                    "AT MOST ONCE is not supported by this endpoint."));
            receiver.close();
        }

        receiver.handler((delivery, message) -> {
                    if (RegistrationMessageFilter.verify(targetAddress, message)) {
                        sendRegistrationData(delivery, message);
                    } else {
                        onLinkDetach(receiver);
                    }
                })
                .closeHandler(clientDetached -> onLinkDetach(clientDetached.result()))
                .open();

        LOG.debug("registering new link for client [{}]", MessageHelper.getLinkName(receiver));
    }

    @Override
    public void onLinkAttach(final ProtonSender sender, final ResourceIdentifier targetResource) {
        /* note: we "misuse" deviceId part of the resource as reply address here */
        if (targetResource.getDeviceId() == null) {
            LOG.debug("Client must provide a reply address e.g. registration/<tenant>/1234-abc");
            sender.setCondition(condition("amqp:invalid-field", "link target must have the following format registration/<tenant>/<reply-address>"));
            sender.close();
        } else {
            final MessageConsumer<JsonObject> replyConsumer = vertx.eventBus().consumer(targetResource.toString(), message -> {
                // TODO check for correct session here...?
                LOG.trace("Forwarding reply to client: {}", message.body());
                final Message amqpReply = RegistrationConstants.getAmqpReply(message);
                sender.send(amqpReply);
            });

            sender.closeHandler(senderClosed -> {
                replyConsumer.unregister();
                senderClosed.result().close();
                final String linkName = MessageHelper.getLinkName(sender);
                LOG.debug("Receiver closed link {}, removing associated event bus consumer {}", linkName, replyConsumer.address());
            });

            sender.open();
        }
    }

    private void onLinkDetach(final ProtonReceiver client) {
        LOG.debug("closing receiver for client [{}]", getLinkName(client));
        client.close();
    }

    private void sendRegistrationData(final ProtonDelivery delivery, final Message msg) {
        vertx.runOnContext(run -> {
            final JsonObject registrationMsg = RegistrationConstants.getRegistrationMsg(msg);
            vertx.eventBus().send(EVENT_BUS_ADDRESS_REGISTRATION_IN, registrationMsg,
                    result -> {
                        // TODO check for correct session here...?
                        final String replyTo = msg.getReplyTo();
                        if (replyTo != null) {
                            final JsonObject message = (JsonObject) result.result().body();
                            final JsonObject correlationIdJson = encodeIdToJson(getCorrelationId(msg));
                            message.put(APP_PROPERTY_CORRELATION_ID, correlationIdJson);
                            vertx.eventBus().send(replyTo, message);
                        } else {
                            LOG.debug("No reply-to address provided, cannot send reply to client.");
                        }
                        ProtonHelper.accepted(delivery, true);
                    });
        });
    }

    /**
    * @param request the request message from which to extract the correlationId
    * @return The ID used to correlate the given request message. This can either be the provided correlationId
    * (Correlation ID Pattern) or the messageId of the request (Message ID Pattern, if no correlationId is provided).
    */
    private Object getCorrelationId(final Message request) {
        final Object correlationId;
        /* if a correlationId is provided, we use it to correlate the response -> Correlation ID Pattern */
        if (request.getCorrelationId() != null) {
            correlationId = request.getCorrelationId();
        } else {
           /* otherwise we use the message id -> Message ID Pattern */
            correlationId = request.getMessageId();
        }
        return correlationId;
    }
}