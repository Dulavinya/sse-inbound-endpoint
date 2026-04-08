/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.inbound.SSE;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.Constants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
public class SequenceToolExecutor {

    private static final Log log = LogFactory.getLog(SequenceToolExecutor.class);

    private final SynapseEnvironment synapseEnvironment;
    private final String sequenceName;

    public SequenceToolExecutor(SynapseEnvironment synapseEnvironment, String sequenceName) {
        this.synapseEnvironment = synapseEnvironment;
        this.sequenceName = sequenceName;
    }

    public String execute(String toolName, JSONObject args, Map<String, Object> toolDefinition)
            throws McpToolExecutionException {

        SynapseConfiguration synapseConfig = synapseEnvironment.getSynapseConfiguration();

        Mediator mediator = synapseConfig.getSequence(sequenceName);
        if (mediator == null) {
            throw new McpToolExecutionException("Sequence not found: " + sequenceName);
        }
        if (!(mediator instanceof SequenceMediator)) {
            throw new McpToolExecutionException("'" + sequenceName + "' is not a sequence mediator");
        }
        SequenceMediator sequence = (SequenceMediator) mediator;

        MessageContext mc;
        try {
            mc = synapseEnvironment.createMessageContext();
            SOAPEnvelope envelope = OMAbstractFactory.getSOAP12Factory().createSOAPEnvelope();
            OMAbstractFactory.getSOAP12Factory().createSOAPBody(envelope);
            mc.setEnvelope(envelope);
        } catch (Exception e) {
            throw new McpToolExecutionException(
                    "Failed to create message context: " + e.getMessage(), e);
        }

        String httpMethod = resolveHttpMethod(toolDefinition);

        injectPayload(mc, args);
        simulateTransportContext(mc, httpMethod);

        if (log.isDebugEnabled()) {
            log.debug("Invoking sequence '" + sequenceName + "' for tool '" + toolName
                    + "' [method=" + httpMethod + "] payload=" + args);
        }

        boolean mediationCompleted;
        try {
            mediationCompleted = sequence.mediate(mc);
        } catch (Exception e) {
            throw new McpToolExecutionException(
                    "Sequence '" + sequenceName + "' threw an exception: " + e.getMessage(), e);
        }

        return extractResult(mc, mediationCompleted);
    }

    private String resolveHttpMethod(Map<String, Object> toolDefinition) {
        if (toolDefinition == null) {
            return "POST";
        }
        Object method = toolDefinition.get("method");
        return (method != null && !method.toString().trim().isEmpty())
                ? method.toString().trim().toUpperCase() : "POST";
    }

    private void injectPayload(MessageContext mc, JSONObject args) throws McpToolExecutionException {
        if (!(mc instanceof Axis2MessageContext)) {
            log.warn("MessageContext is not Axis2MessageContext; skipping payload injection");
            return;
        }
        org.apache.axis2.context.MessageContext axis2Mc =
                ((Axis2MessageContext) mc).getAxis2MessageContext();
        try {
            JsonUtil.getNewJsonPayload(axis2Mc, args.toString(), true, true);
            if (log.isDebugEnabled()) {
                log.debug("Injected JSON payload: " + args);
            }
        } catch (Exception e) {
            throw new McpToolExecutionException(
                    "Failed to inject JSON payload: " + e.getMessage(), e);
        }
    }

    private void simulateTransportContext(MessageContext mc, String httpMethod) {
        if (!(mc instanceof Axis2MessageContext)) {
            return;
        }
        org.apache.axis2.context.MessageContext axis2Mc =
                ((Axis2MessageContext) mc).getAxis2MessageContext();

        axis2Mc.setProperty(Constants.Configuration.MESSAGE_TYPE, "application/json");
        axis2Mc.setProperty(Constants.Configuration.CONTENT_TYPE, "application/json");
        axis2Mc.setProperty("HTTP_METHOD",                         httpMethod);
        axis2Mc.setProperty("REST_URL_POSTFIX",                    "");
        axis2Mc.setProperty("REST_SUB_REQUEST_PATH",               "");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept",       "application/json");
        axis2Mc.setProperty(
                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
    }

    private String extractResult(MessageContext mc, boolean mediationCompleted)
            throws McpToolExecutionException {

        // Synapse fault — standard MI error handling sets ERROR_MESSAGE on the MC
        Object synapseError = mc.getProperty(SynapseConstants.ERROR_MESSAGE);
        if (synapseError != null) {
            Object errCode = mc.getProperty(SynapseConstants.ERROR_CODE);
            String errMsg = (errCode != null ? "[" + errCode + "] " : "") + synapseError;
            log.warn("Sequence '" + sequenceName + "' produced a fault: " + errMsg);
            throw new McpToolExecutionException("Sequence fault: " + errMsg);
        }

        // Response payload left on the message context by the sequence
        String payloadResult = extractPayload(mc);
        if (payloadResult != null && !payloadResult.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("Result from sequence '" + sequenceName + "': " + payloadResult);
            }
            return payloadResult;
        }

        if (!mediationCompleted) {
            log.warn("Sequence '" + sequenceName + "' mediation returned false with no output");
        }

        return "";
    }

    /**
     * Reads the response payload — tries JSON first, then XML SOAP body.
     */
    private String extractPayload(MessageContext mc) {
        if (!(mc instanceof Axis2MessageContext)) {
            return null;
        }
        org.apache.axis2.context.MessageContext axis2Mc =
                ((Axis2MessageContext) mc).getAxis2MessageContext();

        try {
            if (JsonUtil.hasAJsonPayload(axis2Mc)) {
                return JsonUtil.jsonPayloadToString(axis2Mc);
            }
        } catch (Exception e) {
            log.debug("Could not read JSON payload after mediation: " + e.getMessage());
        }

        try {
            org.apache.axiom.soap.SOAPEnvelope envelope = mc.getEnvelope();
            if (envelope != null && envelope.getBody() != null) {
                org.apache.axiom.om.OMElement firstChild = envelope.getBody().getFirstElement();
                if (firstChild != null) {
                    return firstChild.toString();
                }
            }
        } catch (Exception e) {
            log.debug("Could not read SOAP body after mediation: " + e.getMessage());
        }

        return null;
    }
}
