/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn.client;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.yarn.api.ApplicationClientProtocol;
import org.apache.hadoop.yarn.api.ApplicationMasterProtocol;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.server.api.ResourceManagerAdministrationProtocol;

import com.google.common.base.Preconditions;

public class ClientRMProxy<T> extends RMProxy<T>  {
  private static final Log LOG = LogFactory.getLog(ClientRMProxy.class);

  private interface ClientRMProtocols extends ApplicationClientProtocol,
      ApplicationMasterProtocol, ResourceManagerAdministrationProtocol {
    // Add nothing
  }

  static {
    INSTANCE = new ClientRMProxy();
  }

  private ClientRMProxy(){
    super();
  }

  /**
   * Create a proxy to the ResourceManager for the specified protocol.
   * @param configuration Configuration with all the required information.
   * @param protocol Client protocol for which proxy is being requested.
   * @param <T> Type of proxy.
   * @return Proxy to the ResourceManager for the specified client protocol.
   * @throws IOException
   */
  public static <T> T createRMProxy(final Configuration configuration,
      final Class<T> protocol) throws IOException {
    // This method exists only to initiate this class' static INSTANCE. TODO:
    // FIX if possible
    return RMProxy.createRMProxy(configuration, protocol);
  }

  private static void setupTokens(InetSocketAddress resourceManagerAddress)
      throws IOException {
    // It is assumed for now that the only AMRMToken in AM's UGI is for this
    // cluster/RM. TODO: Fix later when we have some kind of cluster-ID as
    // default service-address, see YARN-986.
    for (Token<? extends TokenIdentifier> token : UserGroupInformation
      .getCurrentUser().getTokens()) {
      if (token.getKind().equals(AMRMTokenIdentifier.KIND_NAME)) {
        // This token needs to be directly provided to the AMs, so set the
        // appropriate service-name. We'll need more infrastructure when we
        // need to set it in HA case.
        SecurityUtil.setTokenService(token, resourceManagerAddress);
      }
    }
  }

  @InterfaceAudience.Private
  @Override
  protected InetSocketAddress getRMAddress(YarnConfiguration conf,
      Class<?> protocol) throws IOException {
    if (protocol == ApplicationClientProtocol.class) {
      return conf.getSocketAddr(YarnConfiguration.RM_ADDRESS,
          YarnConfiguration.DEFAULT_RM_ADDRESS,
          YarnConfiguration.DEFAULT_RM_PORT);
    } else if (protocol == ResourceManagerAdministrationProtocol.class) {
      return conf.getSocketAddr(
          YarnConfiguration.RM_ADMIN_ADDRESS,
          YarnConfiguration.DEFAULT_RM_ADMIN_ADDRESS,
          YarnConfiguration.DEFAULT_RM_ADMIN_PORT);
    } else if (protocol == ApplicationMasterProtocol.class) {
      InetSocketAddress serviceAddr =
          conf.getSocketAddr(YarnConfiguration.RM_SCHEDULER_ADDRESS,
            YarnConfiguration.DEFAULT_RM_SCHEDULER_ADDRESS,
            YarnConfiguration.DEFAULT_RM_SCHEDULER_PORT);
      setupTokens(serviceAddr);
      return serviceAddr;
    } else {
      String message = "Unsupported protocol found when creating the proxy " +
          "connection to ResourceManager: " +
          ((protocol != null) ? protocol.getClass().getName() : "null");
      LOG.error(message);
      throw new IllegalStateException(message);
    }
  }

  @InterfaceAudience.Private
  @Override
  protected void checkAllowedProtocols(Class<?> protocol) {
    Preconditions.checkArgument(
        protocol.isAssignableFrom(ClientRMProtocols.class),
        "RM does not support this client protocol");
  }
}
