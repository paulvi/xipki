/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.server.netty;

import java.io.EOFException;

import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.server.api.ResponderManager;
import org.xipki.ca.server.api.CaCmpResponder;
import org.xipki.common.HealthCheckResult;
import org.xipki.common.util.LogUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.http.servlet.AbstractHttpServlet;
import org.xipki.http.servlet.ServletURI;
import org.xipki.http.servlet.SslReverseProxyMode;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class HealthCheckServlet extends AbstractHttpServlet {

  private static final Logger LOG = LoggerFactory.getLogger(HealthCheckServlet.class);

  private static final String CT_RESPONSE = "application/json";

  private ResponderManager responderManager;

  public HealthCheckServlet() {
  }

  public void setResponderManager(ResponderManager responderManager) {
    this.responderManager = ParamUtil.requireNonNull("responderManager", responderManager);
  }

  @Override
  public FullHttpResponse service(FullHttpRequest request, ServletURI servletUri,
      SSLSession sslSession, SslReverseProxyMode sslReverseProxyMode) throws Exception {
    FullHttpResponse resp = service0(request, servletUri, sslSession);
    resp.headers().add("Access-Control-Allow-Origin", "*");
    return resp;
  }

  private FullHttpResponse service0(FullHttpRequest request, ServletURI servletUri,
      SSLSession sslSession) {
    HttpVersion version = request.protocolVersion();
    HttpMethod method = request.method();

    if (method != HttpMethod.GET) {
      return createErrorResponse(version, HttpResponseStatus.METHOD_NOT_ALLOWED);
    }

    try {
      if (responderManager == null) {
        LOG.error("responderManager in servlet is not configured");
        return createErrorResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }

      String caName = null;
      CaCmpResponder responder = null;

      if (servletUri.getPath().length() > 1) {
        // skip the first char which is always '/'
        String caAlias = servletUri.getPath().substring(1);
        caName = responderManager.getCaNameForAlias(caAlias);
        if (caName == null) {
          caName = caAlias.toLowerCase();
        }
        responder = responderManager.getX509CaResponder(caName);
      }

      if (caName == null || responder == null || !responder.isOnService()) {
        String auditMessage;
        if (caName == null) {
          auditMessage = "no CA is specified";
        } else if (responder == null) {
          auditMessage = "unknown CA '" + caName + "'";
        } else {
          auditMessage = "CA '" + caName + "' is out of service";
        }
        LOG.warn(auditMessage);

        return createErrorResponse(version, HttpResponseStatus.NOT_FOUND);
      }

      HealthCheckResult healthResult = responder.healthCheck();
      HttpResponseStatus status = healthResult.isHealthy()
          ? HttpResponseStatus.OK : HttpResponseStatus.INTERNAL_SERVER_ERROR;
      byte[] respBytes = healthResult.toJsonMessage(true).getBytes();
      return createResponse(version, status, HealthCheckServlet.CT_RESPONSE, respBytes);
    } catch (Throwable th) {
      if (th instanceof EOFException) {
        LogUtil.warn(LOG, th, "connection reset by peer");
      } else {
        LOG.error("Throwable thrown, this should not happen!", th);
      }
      return createErrorResponse(version, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
  } // method service0

}
