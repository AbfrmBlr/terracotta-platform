/**
 * Copyright Terracotta, Inc.
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
 */
package org.terracotta.consensus.entity;

import org.terracotta.consensus.entity.messages.ServerElectionEvent;
import org.terracotta.consensus.entity.server.ElectionChangeListener;
import org.terracotta.consensus.entity.server.LeaderElector;
import org.terracotta.entity.ClientCommunicator;
import org.terracotta.entity.ClientDescriptor;
import org.terracotta.voltron.proxy.server.ProxiedServerEntity;

/**
 * @author Alex Snaps
 */
public class CoordinationServerEntity extends ProxiedServerEntity<CoordinationEntity> {

  private final LeaderElector<String, ClientDescriptor> leaderElector;

  public CoordinationServerEntity(final LeaderElector<String, ClientDescriptor> leaderElector, final ClientCommunicator clientCommunicator) {
    super(new ServerCoordinationImpl(leaderElector, ServerElectionEvent.class), clientCommunicator, ServerElectionEvent.class);
    this.leaderElector = leaderElector;
    this.leaderElector.setListener(new ElectionChangeListenerImpl());
  }

  @Override
  public void disconnected(final ClientDescriptor clientDescriptor) {
    super.disconnected(clientDescriptor);
    leaderElector.delistAll(clientDescriptor);
  }

  private class ElectionChangeListenerImpl implements ElectionChangeListener<String, ClientDescriptor> {

    @Override
    public void onDelist(String namespace, ClientDescriptor clientDescriptor) {
      fireAndForgetMessage(ServerElectionEvent.changed(namespace), clientDescriptor);
    }
  }
}
