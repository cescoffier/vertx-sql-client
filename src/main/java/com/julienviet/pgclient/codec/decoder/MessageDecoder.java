/*
 * Copyright (C) 2017 Julien Viet
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
 *
 */

package com.julienviet.pgclient.codec.decoder;

import com.julienviet.pgclient.codec.Column;
import com.julienviet.pgclient.codec.DataFormat;
import com.julienviet.pgclient.codec.DataType;
import com.julienviet.pgclient.codec.TransactionStatus;
import com.julienviet.pgclient.codec.decoder.message.*;
import com.julienviet.pgclient.codec.util.Util;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ByteProcessor;
import io.vertx.core.json.JsonArray;

import java.util.Deque;
import java.util.List;

import static com.julienviet.pgclient.codec.decoder.message.type.AuthenticationType.*;
import static com.julienviet.pgclient.codec.decoder.message.type.ErrorOrNoticeType.*;
import static com.julienviet.pgclient.codec.decoder.message.type.MessageType.*;

/**
 *
 * Decoder for <a href="https://www.postgresql.org/docs/9.5/static/protocol.html">PostgreSQL protocol</a>
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class MessageDecoder extends ByteToMessageDecoder {

  private final Deque<DecodeContext> decodeQueue;
  private RowDescription rowDesc;

  public MessageDecoder(Deque<DecodeContext> decodeQueue) {
    this.decodeQueue = decodeQueue;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    while (true) {
      if (in.readableBytes() == 1) {
        switch (in.getByte(0)) {
          case SSL_YES: {
            out.add(new SSLResponse(true));
            in.readerIndex(in.readerIndex() + 1);
            return;
          }
          case SSL_NO: {
            out.add(new SSLResponse(false));
            in.readerIndex(in.readerIndex() + 1);
            return;
          }
        }
      }
      if (in.readableBytes() < 5) {
        break;
      }
      int beginIdx = in.readerIndex();
      byte id = in.getByte(beginIdx);
      int length = in.getInt(beginIdx + 1);
      int endIdx = beginIdx + length + 1;
      final int writerIndex = in.writerIndex();
      if (writerIndex < endIdx) {
        break;
      }
      try {
        in.setIndex(beginIdx + 5, beginIdx + 1 + length);
        decodeMessage(id, in, out);
      } finally {
        in.setIndex(endIdx, writerIndex);
      }
    }
  }

  private void decodeMessage(byte id, ByteBuf in, List<Object> out) {
    switch (id) {
      case ERROR_RESPONSE: {
        decodeErrorOrNotice(ErrorResponse.INSTANCE, in, out);
        break;
      }
      case NOTICE_RESPONSE: {
        decodeErrorOrNotice(NoticeResponse.INSTANCE, in, out);
        break;
      }
      case AUTHENTICATION: {
        decodeAuthentication(in, out);
      }
      break;
      case READY_FOR_QUERY: {
        decodeReadyForQuery(in, out);
        DecodeContext decodeCtx = decodeQueue.poll();
        if (decodeCtx == null) {
          throw new AssertionError(); // For debugging purposes
        }
      }
      break;
      case ROW_DESCRIPTION: {
        Column[] columns = decodeRowDescription(in);
        rowDesc = new RowDescription(columns);
        out.add(rowDesc);
      }
      break;
      case DATA_ROW: {
        DecodeContext decodeCtx = decodeQueue.peek();
        RowDescription desc = decodeCtx.peekDesc ? rowDesc : decodeCtx.rowDesc;
        int len = in.readUnsignedShort();
        Object row = decodeCtx.decoder.createRow(len);
        for (int c = 0; c < len; ++c) {
          int length = in.readInt();
          if (length != -1) {
            Column columnDesc = desc.getColumns()[c];
            DataType.Decoder decoder = columnDesc.getCodec();
            decodeCtx.decoder.decode(in, length, decoder, row);
          } else {
            decodeCtx.decoder.decode(in, length, null, row);
          }
        }
        decodeCtx.decoder.addRow(row);
      }
      break;
      case COMMAND_COMPLETE: {
        decodeQueue.peek().decoder.complete();
        CommandComplete complete = decodeCommandComplete(in);
        out.add(complete);
      }
      break;
      case EMPTY_QUERY_RESPONSE: {
        decodeEmptyQueryResponse(out);
      }
      break;
      case PARSE_COMPLETE: {
        decodeParseComplete(out);
      }
      break;
      case BIND_COMPLETE: {
        decodeBindComplete(out);
      }
      break;
      case CLOSE_COMPLETE: {
        decodeCloseComplete(out);
      }
      break;
      case NO_DATA: {
        decodeNoData(out);
      }
      break;
      case PORTAL_SUSPENDED: {
        decodeQueue.peek().decoder.complete();
        decodePortalSuspended(out);
      }
      break;
      case PARAMETER_DESCRIPTION: {
        decodeParameterDescription(in, out);
      }
      break;
      case PARAMETER_STATUS: {
        decodeParameterStatus(in, out);
      }
      break;
      case BACKEND_KEY_DATA: {
        decodeBackendKeyData(in, out);
      }
      break;
      case NOTIFICATION_RESPONSE: {
        decodeNotificationResponse(in, out);
      }
      break;
    }
  }

  private void decodeErrorOrNotice(Response response, ByteBuf in, List<Object> out) {

    byte type;

    while ((type = in.readByte()) != 0) {

      switch (type) {

        case SEVERITY:
          response.setSeverity(Util.readCStringUTF8(in));
          break;

        case CODE:
          response.setCode(Util.readCStringUTF8(in));
          break;

        case MESSAGE:
          response.setMessage(Util.readCStringUTF8(in));
          break;

        case DETAIL:
          response.setDetail(Util.readCStringUTF8(in));
          break;

        case HINT:
          response.setHint(Util.readCStringUTF8(in));
          break;

        case INTERNAL_POSITION:
          response.setInternalPosition(Util.readCStringUTF8(in));
          break;

        case INTERNAL_QUERY:
          response.setInternalQuery(Util.readCStringUTF8(in));
          break;

        case POSITION:
          response.setPosition(Util.readCStringUTF8(in));
          break;

        case WHERE:
          response.setWhere(Util.readCStringUTF8(in));
          break;

        case FILE:
          response.setFile(Util.readCStringUTF8(in));
          break;

        case LINE:
          response.setLine(Util.readCStringUTF8(in));
          break;

        case ROUTINE:
          response.setRoutine(Util.readCStringUTF8(in));
          break;

        case SCHEMA:
          response.setSchema(Util.readCStringUTF8(in));
          break;

        case TABLE:
          response.setTable(Util.readCStringUTF8(in));
          break;

        case COLUMN:
          response.setColumn(Util.readCStringUTF8(in));
          break;

        case DATA_TYPE:
          response.setDataType(Util.readCStringUTF8(in));
          break;

        case CONSTRAINT:
          response.setConstraint(Util.readCStringUTF8(in));
          break;

        default:
          Util.readCStringUTF8(in);
          break;
      }
    }
    out.add(response);
  }

  private void decodeAuthentication(ByteBuf in, List<Object> out) {

    int type = in.readInt();
    switch (type) {
      case OK: {
        out.add(AuthenticationOk.INSTANCE);
      }
      break;
      case MD5_PASSWORD: {
        byte[] salt = new byte[4];
        in.readBytes(salt);
        out.add(new AuthenticationMD5Password(salt));
      }
      break;
      case CLEARTEXT_PASSWORD: {
        out.add(AuthenticationClearTextPassword.INSTANCE);
      }
      break;
      case KERBEROS_V5:
      case SCM_CREDENTIAL:
      case GSS:
      case GSS_CONTINUE:
      case SSPI:
      default:
        throw new UnsupportedOperationException("Authentication type is not supported in the client");
    }
  }

  private CommandCompleteProcessor processor = new CommandCompleteProcessor();

  static class CommandCompleteProcessor implements ByteProcessor {
    private static final byte SPACE = 32;
    private int rows;
    boolean afterSpace;
    int parse(ByteBuf in) {
      afterSpace = false;
      rows = 0;
      in.forEachByte(in.readerIndex(), in.readableBytes() - 1, this);
      return rows;
    }
    @Override
    public boolean process(byte value) throws Exception {
      boolean space = value == SPACE;
      if (afterSpace) {
        if (space) {
          rows = 0;
        } else {
          rows = rows * 10 + (value - '0');
        }
      } else {
        afterSpace = space;
      }
      return true;
    }
  }

  private CommandComplete decodeCommandComplete(ByteBuf in) {
    int rows = processor.parse(in);
    return rows == 0 ? CommandComplete.EMPTY : new CommandComplete(rows);
  }

  private Column[]  decodeRowDescription(ByteBuf in) {
    Column[] columns = new Column[in.readUnsignedShort()];
    for (int c = 0; c < columns.length; ++c) {
      String fieldName = Util.readCStringUTF8(in);
      int tableOID = in.readInt();
      short columnAttributeNumber = in.readShort();
      int typeOID = in.readInt();
      short typeSize = in.readShort();
      int typeModifier = in.readInt();
      int textOrBinary = in.readUnsignedShort(); // Useless for now
      Column column = new Column(
        fieldName,
        tableOID,
        columnAttributeNumber,
        DataType.valueOf(typeOID),
        typeSize,
        typeModifier,
        DataFormat.valueOf(textOrBinary)
      );
      columns[c] = column;
    }
    return columns;
  }

  private void decodeReadyForQuery(ByteBuf in, List<Object> out) {
    out.add(new ReadyForQuery(TransactionStatus.valueOf(in.readByte())));
  }

  private void decodeParseComplete(List<Object> out) {
    out.add(ParseComplete.INSTANCE);
  }

  private void decodeBindComplete(List<Object> out) {
    out.add(BindComplete.INSTANCE);
  }

  private void decodeCloseComplete(List<Object> out) {
    out.add(CloseComplete.INSTANCE);
  }

  private void decodeNoData(List<Object> out) {
    out.add(NoData.INSTANCE);
  }

  private void decodePortalSuspended(List<Object> out) {
    out.add(PortalSuspended.INSTANCE);
  }

  private void decodeParameterDescription(ByteBuf in, List<Object> out) {
    DataType[] paramDataTypes = new DataType[in.readUnsignedShort()];
    for (int c = 0; c < paramDataTypes.length; ++c) {
      paramDataTypes[c] = DataType.valueOf(in.readInt());
    }
    out.add(new ParameterDescription(paramDataTypes));
  }

  private void decodeParameterStatus(ByteBuf in, List<Object> out) {
    out.add(new ParameterStatus(Util.readCStringUTF8(in), Util.readCStringUTF8(in)));
  }

  private void decodeEmptyQueryResponse(List<Object> out) {
    out.add(EmptyQueryResponse.INSTANCE);
  }

  private void decodeBackendKeyData(ByteBuf in, List<Object> out) {
    out.add(new BackendKeyData(in.readInt(), in.readInt()));
  }

  private void decodeNotificationResponse(ByteBuf in, List<Object> out) {
    out.add(new NotificationResponse(in.readInt(), Util.readCStringUTF8(in), Util.readCStringUTF8(in)));
  }
}
