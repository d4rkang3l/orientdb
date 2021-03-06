/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.client.remote.message;

import java.io.IOException;
import java.util.Set;

import com.orientechnologies.orient.client.remote.OBinaryResponse;
import com.orientechnologies.orient.client.remote.OStorageRemoteSession;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.serialization.serializer.record.ORecordSerializer;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.ORecordSerializerNetworkV37;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryProtocol;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelDataInput;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelDataOutput;

public class OReadRecordIfVersionIsNotLatestResponse implements OBinaryResponse {

  private byte         recordType;
  private int          version;
  private byte[]       record;
  private Set<ORecord> recordsToSend;
  private ORawBuffer   result;

  public OReadRecordIfVersionIsNotLatestResponse() {
  }

  public OReadRecordIfVersionIsNotLatestResponse(byte recordType, int version, byte[] record, Set<ORecord> recordsToSend) {
    this.recordType = recordType;
    this.version = version;
    this.record = record;
    this.recordsToSend = recordsToSend;
  }

  public void write(OChannelDataOutput network, int protocolVersion, ORecordSerializer serializer) throws IOException {
    if (record != null) {
      network.writeByte((byte) 1);
      if (protocolVersion <= OChannelBinaryProtocol.PROTOCOL_VERSION_27) {
        network.writeBytes(record);
        network.writeVersion(version);
        network.writeByte(recordType);
      } else {
        network.writeByte(recordType);
        network.writeVersion(version);
        network.writeBytes(record);
      }
      for (ORecord d : recordsToSend) {
        if (d.getIdentity().isValid()) {
          network.writeByte((byte) 2); // CLIENT CACHE
          // RECORD. IT ISN'T PART OF THE RESULT SET
          OMessageHelper.writeRecord(network, d, serializer);
        }
      }
    }
    // End of the response
    network.writeByte((byte) 0);
  }

  @Override
  public void read(OChannelDataInput network, OStorageRemoteSession session) throws IOException {
    ORecordSerializer serializer = ORecordSerializerNetworkV37.INSTANCE;
    if (network.readByte() == 0)
      return;

    byte type = network.readByte();
    int recVersion = network.readVersion();
    byte[] bytes = network.readBytes();
    ORawBuffer buffer = new ORawBuffer(bytes, recVersion, type);

    final ODatabaseDocument database = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
    ORecord record;

    while (network.readByte() == 2) {
      record = (ORecord) OMessageHelper.readIdentifiable(network, serializer);

      if (database != null)
        // PUT IN THE CLIENT LOCAL CACHE
        database.getLocalCache().updateRecord(record);
    }
    result = buffer;
  }

  public ORawBuffer getResult() {
    return result;
  }

}