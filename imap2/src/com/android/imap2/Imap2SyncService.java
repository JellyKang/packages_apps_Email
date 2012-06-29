/* Copyright (C) 2012 The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.imap2;

import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.CertificateValidationException;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.MailboxUtilities;
import com.android.emailcommon.provider.ProviderUnavailableException;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.SyncWindow;
import com.android.emailcommon.utility.SSLUtils;
import com.android.emailcommon.utility.TextUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.emailsync.AbstractSyncService;
import com.android.emailsync.PartRequest;
import com.android.emailsync.Request;
import com.android.emailsync.SyncManager;
import com.android.mail.providers.UIProvider;
import com.beetstra.jutf7.CharsetProvider;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

public class Imap2SyncService extends AbstractSyncService {

    private static final String IMAP_OK = "OK";
    private static final SimpleDateFormat GMAIL_INTERNALDATE_FORMAT =
            new SimpleDateFormat("EEE, dd MMM yy HH:mm:ss z");
    private static final String IMAP_ERR = "ERR";

    private static final SimpleDateFormat IMAP_DATE_FORMAT =
            new SimpleDateFormat("dd-MMM-yyyy");
    private static final SimpleDateFormat INTERNALDATE_FORMAT =
            new SimpleDateFormat("dd-MMM-yy HH:mm:ss z");
    private static final Charset MODIFIED_UTF_7_CHARSET =
            new CharsetProvider().charsetForName("X-RFC-3501");

    public static final String IMAP_DELETED_MESSAGES_FOLDER_NAME = "AndroidMail Trash";
    public static final String GMAIL_TRASH_FOLDER = "[Gmail]/Trash";

    private static Pattern IMAP_RESPONSE_PATTERN = Pattern.compile("\\*(\\s(\\d+))?\\s(\\w+).*");

    private static final int HEADER_BATCH_COUNT = 10;

    //  private static final int IDLE_TIMEOUT_MILLIS = 12*MINS;
    private static final int SECONDS = 1000;
    private static final int MINS = 60*SECONDS;
    private static final int IDLE_ASLEEP_MILLIS = 11*MINS;
    //  private static final int COMMAND_TIMEOUT_MILLIS = 24*SECS;

    private static final int SOCKET_CONNECT_TIMEOUT = 10*SECONDS;
    private static final int SOCKET_TIMEOUT = 20*SECONDS;

    private ContentResolver mResolver;
    private int mWriterTag = 1;
    private boolean mIsGmail = false;
    private boolean mIsIdle = false;
    private int mLastExists = -1;

    private ArrayList<String> mImapResponse = null;
    private String mImapResult;
    private String mImapErrorLine = null;

    private Socket mSocket = null;
    private boolean mStop = false;

    public int mServiceResult = 0;
    private boolean mIsServiceRequestPending = false;

    private final String[] MAILBOX_SERVER_ID_ARGS = new String[2];
    public Imap2SyncService() {
        this("Imap2 Validation");
    }

    private final ArrayList<Integer> SERVER_DELETES = new ArrayList<Integer>();

    private static final String INBOX_SERVER_NAME = "Inbox"; // Per RFC3501

    private BufferedWriter mWriter;
    private ImapInputStream mReader;

    private HostAuth mHostAuth;
    private String mPrefix;

    public Imap2SyncService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
        mResolver = _context.getContentResolver();
        MAILBOX_SERVER_ID_ARGS[0] = Long.toString(mMailboxId);
    }

    private Imap2SyncService(String prefix) {
        super(prefix);
    }

    public Imap2SyncService(Context _context, Account _account) {
        this("Imap2 Account");
        mContext = _context;
        mResolver = _context.getContentResolver();
        mAccount = _account;
        mHostAuth = HostAuth.restoreHostAuthWithId(_context, mAccount.mHostAuthKeyRecv);
        mPrefix = mHostAuth.mDomain;
    }

    @Override
    public boolean alarm() {
        // See if we've got anything to do...
        Cursor updates = getUpdatesCursor();
        Cursor deletes = getDeletesCursor();
        try {
            if (mRequestQueue.isEmpty() && updates == null && deletes == null) {
                userLog("Ping: nothing to do");
            } else {
                int cnt = mRequestQueue.size();
                if (updates != null) {
                    cnt += updates.getCount();
                }
                if (deletes != null) {
                    cnt += deletes.getCount();
                }
                userLog("Ping: " + cnt + " tasks");
                ping();
            }
        } finally {
            if (updates != null) {
                updates.close();
            }
            if (deletes != null) {
                deletes.close();
            }
        }
        return true;
    }

    @Override
    public void reset() {
        // TODO Auto-generated method stub
    }

    public void addRequest(Request req) {
        super.addRequest(req);
        if (req instanceof PartRequest) {
            userLog("Request for attachment " + ((PartRequest)req).mAttachment.mId);
        }
        ping();
    }

    @Override
    public Bundle validateAccount(HostAuth hostAuth, Context context) {
        Bundle bundle = new Bundle();
        int resultCode = MessagingException.IOERROR;

        Connection conn = connectAndLogin(hostAuth, "main");
        if (conn.status == EXIT_DONE) {
            resultCode = MessagingException.NO_ERROR;
        } else if (conn.status == EXIT_LOGIN_FAILURE) {
            resultCode = MessagingException.AUTHENTICATION_FAILED;
        }

        bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE, resultCode);
        return bundle;
    }

    public void loadFolderList() throws IOException {
        HostAuth hostAuth =
                HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        if (hostAuth == null) return;
        Connection conn = connectAndLogin(hostAuth, "folderList");
        if (conn.status == EXIT_DONE) {
            setConnection(conn);
            readFolderList();
            conn.socket.close();
        }
    }

    private void setConnection(Connection conn) {
        mConnection = conn;
        mWriter = conn.writer;
        mReader = conn.reader;
        mSocket = conn.socket;
    }

    @Override
    public void resetCalendarSyncKey() {
        // Not used by Imap2
    }

    public void ping() {
        mIsServiceRequestPending = true;
        Imap2SyncManager.runAwake(mMailbox.mId);
        if (mSocket != null) {
            try {
                if (mIsIdle) {
                    userLog("breakIdle; sending DONE...");
                    mWriter.write("DONE\r\n");
                    mWriter.flush();
                }
            } catch (SocketException e) {
            } catch (IOException e) {
            }
        }
    }

    public void stop () {
        if (mSocket != null)
            try {
                if (mIsIdle)
                    ping();
                mSocket.close();
            } catch (IOException e) {
            }
        mStop = true;
    }

    public String writeCommand (Writer out, String cmd) {
        try {
            Integer t = mWriterTag++;
            String tag = "@@a" + t + ' ';
            out.write(tag);
            out.write(cmd);
            out.write("\r\n");
            out.flush();
            if (!cmd.startsWith("login"))
                userLog(tag + cmd);
            return tag;
        } catch (IOException e) {
            userLog("IOException in writeCommand");
        }
        return null;
    }

    private long readLong (String str, int idx) {
        char ch = str.charAt(idx);
        long num = 0;
        while (ch >= '0' && ch <= '9') {
            num = (num * 10) + (ch - '0');
            ch = str.charAt(++idx);
        }
        return num;
    }

    private void readUntagged(String str) {
        // Skip the "* "
        Parser p = new Parser(str, 2);
        String type = p.parseAtom();
        int val = -1;
        if (type != null) {
            char c = type.charAt(0);
            if (c >= '0' && c <= '9')
                try {
                    val = Integer.parseInt(type);
                    type = p.parseAtom();
                    if (p != null) {
                        if (type.toLowerCase().equals("exists"))
                            mLastExists = val;
                    }
                } catch (NumberFormatException e) {
                } else if (mMailbox.mSyncKey == null || mMailbox.mSyncKey == "0") {
                    str = str.toLowerCase();
                    int idx = str.indexOf("uidvalidity");
                    if (idx > 0) {
                        //*** 12?
                        long num = readLong(str, idx + 12);
                        mMailbox.mSyncKey = Long.toString(num);
                        ContentValues cv = new ContentValues();
                        cv.put(MailboxColumns.SYNC_KEY, mMailbox.mSyncKey);
                        mContext.getContentResolver().update(
                                ContentUris.withAppendedId(Mailbox.CONTENT_URI, mMailbox.mId), cv,
                                null, null);
                    }
                }
        }

        userLog("Untagged: " + type);
    }

    private boolean caseInsensitiveStartsWith(String str, String tag) {
        return str.toLowerCase().startsWith(tag.toLowerCase());
    }

    private String readResponse (ImapInputStream r, String tag) throws IOException {
        return readResponse(r, tag, null);
    }

    private String readResponse (ImapInputStream r, String tag, String command) throws IOException {
        mImapResult = IMAP_ERR;
        String str = null;
        if (command != null)
            mImapResponse = new ArrayList<String>();
        while (true) {
            str = r.readLine();
            userLog("< " + str);
            if (caseInsensitiveStartsWith(str, tag)) {
                // This is the response from the command named 'tag'
                Parser p = new Parser(str, tag.length() - 1);
                mImapResult = p.parseAtom();
                break;
            } else if (str.charAt(0) == '*') {
                if (command != null) {
                    Matcher m = IMAP_RESPONSE_PATTERN.matcher(str);
                    if (m.matches() && m.group(3).equals(command)) {
                        mImapResponse.add(str);
                    } else
                        readUntagged(str);
                } else
                    readUntagged(str);
            } else if (!mImapResponse.isEmpty()) {
                // Continuation with string literal, perhaps?
                int off = mImapResponse.size() - 1;
                mImapResponse.set(off, mImapResponse.get(off) + "\r\n" + str);
            }
        }

        if (!mImapResult.equals(IMAP_OK)) {
            userLog("$$$ Error result = " + mImapResult);
            mImapErrorLine = str;
        }
        return mImapResult;
    }

    String parseRecipientList (String str) {
        if (str == null)
            return null;
        ArrayList<Address> list = new ArrayList<Address>();
        String r;
        Parser p = new Parser(str);
        while ((r = p.parseList()) != null) {
            Parser rp = new Parser(r);
            String displayName = rp.parseString();
            rp.parseString();
            String emailAddress = rp.parseString() + "@" + rp.parseString();
            list.add(new Address(emailAddress, displayName));
        }
        return Address.pack(list.toArray(new Address[list.size()]));
    }

    String parseRecipients (Parser p, Message msg) {
        msg.mFrom = parseRecipientList(p.parseListOrNil());
        @SuppressWarnings("unused")
        String senderList = parseRecipientList(p.parseListOrNil());
        msg.mReplyTo = parseRecipientList(p.parseListOrNil());
        msg.mTo = parseRecipientList(p.parseListOrNil());
        msg.mCc = parseRecipientList(p.parseListOrNil());
        msg.mBcc = parseRecipientList(p.parseListOrNil());
        return Address.toFriendly(Address.unpack(msg.mFrom));
    }

    private Message createMessage (String str) {
        Parser p = new Parser(str, str.indexOf('(') + 1);
        Date date = null;
        String subject = null;
        String sender = null;
        boolean read = false;
        int flag = 0;
        String flags = null;
        int uid = 0;
        boolean bodystructure = false;

        Message msg = new Message();
        msg.mMailboxKey = mMailboxId;

        try {
            while (true) {
                String atm = p.parseAtom();
                // We're done if we have all of these, regardless of order
                if (date != null && flags != null && bodystructure)
                    break;
                // Not sure if this case is possible
                if (atm == null)
                    break;
                if (atm.equalsIgnoreCase("UID")) {
                    uid = p.parseInteger();
                    //userLog("UID=" + uid);
                } else if (atm.equalsIgnoreCase("ENVELOPE")) {
                    String envelope = p.parseList();
                    Parser ep = new Parser(envelope);
                    ep.skipWhite();
                    //date = parseDate(ep.parseString());
                    ep.parseString();
                    subject = ep.parseString();
                    sender = parseRecipients(ep, msg);
                } else if (atm.equalsIgnoreCase("FLAGS")) {
                    flags = p.parseList().toLowerCase();
                    if (flags.indexOf("\\seen") >=0)
                        read = true;
                    if (flags.indexOf("\\flagged") >=0)
                        flag = 1;
                } else if (atm.equalsIgnoreCase("BODYSTRUCTURE")) {
                    msg.mSyncData = p.parseList();
                    bodystructure = true;
                    //parseBodystructure(msg, new Parser(bs), "", 1, parts);
                } else if (atm.equalsIgnoreCase("INTERNALDATE")) {
                    date = parseInternaldate(p.parseString());
                }
            }
        } catch (Exception e) {
            // Parsing error here.  We've got one known one from EON
            // in which BODYSTRUCTURE is ( "MIXED" (....) )
            if (sender == null)
                sender = "Unknown sender";
            if (subject == null)
                subject = "No subject";
            e.printStackTrace();
        }

        if (subject != null && subject.startsWith("=?"))
            subject = MimeUtility.decode(subject);
        msg.mSubject = subject;

        //msg.bodyId = 0;
        //msg.parts = parts.toString();
        msg.mAccountKey = mAccount.mId;

        msg.mFlagLoaded = Message.FLAG_LOADED_UNLOADED;
        msg.mFlags = flag;
        if (read)
            msg.mFlagRead = true;
        msg.mTimeStamp = ((date != null) ? date : new Date()).getTime();
        msg.mServerId = Long.toString(uid);
        return msg;
    }

    private Date parseInternaldate (String str) {
        if (str != null) {
            SimpleDateFormat f = INTERNALDATE_FORMAT;
            if (str.charAt(3) == ',')
                f = GMAIL_INTERNALDATE_FORMAT;
            try {
                return f.parse(str);
            } catch (ParseException e) {
                userLog("Unparseable date: " + str);
            }
        }
        return new Date();
    }

    private long getIdForUid(int uid) {
        // TODO: Rename this
        MAILBOX_SERVER_ID_ARGS[1] = Integer.toString(uid);
        Cursor c = mResolver.query(Message.CONTENT_URI, Message.ID_COLUMN_PROJECTION,
                MessageColumns.MAILBOX_KEY + "=? AND " + SyncColumns.SERVER_ID + "=?",
                MAILBOX_SERVER_ID_ARGS, null);
        try {
            if (c != null && c.moveToFirst()) {
                return c.getLong(Message.ID_COLUMNS_ID_COLUMN);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return Message.NO_MESSAGE;
    }

    private void processDelete(int uid) {
        SERVER_DELETES.clear();
        SERVER_DELETES.add(uid);
        processServerDeletes(SERVER_DELETES);
    }

    /**
     * Handle a single untagged line
     * TODO: Perhaps batch operations for multiple lines into a single transaction
     */
    private boolean handleUntagged (String line) {
        line = line.toLowerCase();
        Matcher m = IMAP_RESPONSE_PATTERN.matcher(line);
        boolean res = false;
        if (m.matches()) {
            // What kind of thing is this?
            String type = m.group(3);
            if (type.equals("fetch") || type.equals("expunge")) {
                // This is a flag change or an expunge.  First, find the UID
                int uid = 0;
                // TODO Get rid of hack to avoid uid...
                int uidPos = line.indexOf("uid");
                if (uidPos > 0) {
                    Parser p = new Parser(line, uidPos + 3);
                    uid = p.parseInteger();
                }

                if (uid == 0) {
                    // This will be very inefficient
                    // Have to 1) break idle, 2) query the server for uid
                    return false;
                }
                long id = getIdForUid(uid);
                if (id == Message.NO_MESSAGE) {
                    // Nothing to do; log
                    userLog("? No message found for uid " + uid);
                    return true;
                }

                if (type.equals("fetch")) {
                    if (line.indexOf("\\deleted") > 0) {
                        processDelete(uid);
                    } else {
                        boolean read = line.indexOf("\\seen") > 0;
                        boolean flagged = line.indexOf("\\flagged") > 0;
                        // TODO: Reuse
                        ContentValues values = new ContentValues();
                        values.put(MessageColumns.FLAG_READ, read);
                        values.put(MessageColumns.FLAG_FAVORITE, flagged);
                        mResolver.update(ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, id),
                                values, null, null);
                    }
                    userLog("<<< FLAGS " + uid);
                } else {
                    userLog("<<< EXPUNGE " + uid);
                    processDelete(uid);
                }
            } else if (type.equals("exists")) {
                int num = Integer.parseInt(m.group(2));
                if (mIsGmail && (num == mLastExists)) {
                    userLog("Gmail: nothing new...");
                    return false;
                }
                else if (mIsGmail)
                    mLastExists = num;
                res = true;
                userLog("<<< EXISTS tag; new SEARCH");
            }
        }

        return res;
    }   

    /**
     * Prepends the folder name with the given prefix and UTF-7 encodes it.
     */
    private String encodeFolderName(String name) {
        // do NOT add the prefix to the special name "INBOX"
        if ("inbox".equalsIgnoreCase(name)) return name;

        // Prepend prefix
        if (mPrefix != null) {
            name = mPrefix + name;
        }

        // TODO bypass the conversion if name doesn't have special char.
        ByteBuffer bb = MODIFIED_UTF_7_CHARSET.encode(name);
        byte[] b = new byte[bb.limit()];
        bb.get(b);

        return Utility.fromAscii(b);
    }

    /**
     * UTF-7 decodes the folder name and removes the given path prefix.
     */
    static String decodeFolderName(String name, String prefix) {
        // TODO bypass the conversion if name doesn't have special char.
        String folder;
        folder = MODIFIED_UTF_7_CHARSET.decode(ByteBuffer.wrap(Utility.toAscii(name))).toString();
        if ((prefix != null) && folder.startsWith(prefix)) {
            folder = folder.substring(prefix.length());
        }
        return folder;
    }

    private static class ServerUpdate {
        final long id;
        final int serverId;

        ServerUpdate(long _id, int _serverId) {
            id = _id;
            serverId = _serverId;
        }
    }

    private ArrayList<Long> mUpdatedIds = new ArrayList<Long>();
    private ArrayList<Long> mDeletedIds = new ArrayList<Long>();
    private Stack<ServerUpdate> mDeletes = new Stack<ServerUpdate>();
    private Stack<ServerUpdate> mReadUpdates = new Stack<ServerUpdate>();
    private Stack<ServerUpdate> mUnreadUpdates = new Stack<ServerUpdate>();
    private Stack<ServerUpdate> mFlaggedUpdates = new Stack<ServerUpdate>();
    private Stack<ServerUpdate> mUnflaggedUpdates = new Stack<ServerUpdate>();

    private Cursor getUpdatesCursor() {
        Cursor c = mResolver.query(Message.UPDATED_CONTENT_URI, UPDATE_DELETE_PROJECTION,
                MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId, null, null);
        if (c == null || c.getCount() == 0) {
            c.close();
            return null;
        }
        return c;
    }

    private static final String[] UPDATE_DELETE_PROJECTION =
            new String[] {MessageColumns.ID, SyncColumns.SERVER_ID, MessageColumns.MAILBOX_KEY,
        MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE};
    private static final int UPDATE_DELETE_ID_COLUMN = 0;
    private static final int UPDATE_DELETE_SERVER_ID_COLUMN = 1;
    private static final int UPDATE_DELETE_MAILBOX_KEY_COLUMN = 2;
    private static final int UPDATE_DELETE_READ_COLUMN = 3;
    private static final int UPDATE_DELETE_FAVORITE_COLUMN = 4;

    private Cursor getDeletesCursor() {
        Cursor c = mResolver.query(Message.DELETED_CONTENT_URI, UPDATE_DELETE_PROJECTION,
                MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId, null, null);
        if (c == null || c.getCount() == 0) {
            c.close();
            return null;
        }
        return c;
    }

    private void handleLocalDeletes() throws IOException {
        Cursor c = getDeletesCursor();
        if (c == null) return;
        mDeletes.clear();
        mDeletedIds.clear();

        try {
            while (c.moveToNext()) {
                long id = c.getLong(UPDATE_DELETE_ID_COLUMN);
                mDeletes.add(new ServerUpdate(id, c.getInt(UPDATE_DELETE_SERVER_ID_COLUMN)));
                mDeletedIds.add(id);
            }
            sendUpdate(mDeletes, "+FLAGS (\\Deleted)");
            String tag = writeCommand(mConnection.writer, "expunge");
            readResponse(mConnection.reader, tag, "expunge");

            // Delete the deletions now (we must go deeper!)
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            for (long id: mDeletedIds) {
                ops.add(ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(
                                Message.DELETED_CONTENT_URI, id)).build());
            }
            applyBatch(ops);
        } finally {
            c.close();
        }
    }

    private void handleLocalUpdates() throws IOException {
        Cursor updatesCursor = getUpdatesCursor();
        if (updatesCursor == null) return;

        mUpdatedIds.clear();
        mReadUpdates.clear();
        mUnreadUpdates.clear();
        mFlaggedUpdates.clear();
        mUnflaggedUpdates.clear();

        try {
            while (updatesCursor.moveToNext()) {
                long id = updatesCursor.getLong(UPDATE_DELETE_ID_COLUMN);
                // Keep going if there's no serverId
                int serverId = updatesCursor.getInt(UPDATE_DELETE_SERVER_ID_COLUMN);
                if (serverId == 0) {
                    continue;
                }

                // Say we've handled this update
                mUpdatedIds.add(id);
                // We have the id of the changed item.  But first, we have to find out its current
                // state, since the updated table saves the opriginal state
                Cursor currentCursor = mResolver.query(
                        ContentUris.withAppendedId(Message.CONTENT_URI, id),
                        UPDATE_DELETE_PROJECTION, null, null, null);
                try {
                    // If this item no longer exists (shouldn't be possible), just move along
                    if (!currentCursor.moveToFirst()) {
                        continue;
                    }

                    boolean flagChange = false;
                    boolean readChange = false;

                    long mailboxId = currentCursor.getLong(UPDATE_DELETE_MAILBOX_KEY_COLUMN);
                    // If the message is now in the trash folder, it has been deleted by the user
                    if (mailboxId != updatesCursor.getLong(UPDATE_DELETE_MAILBOX_KEY_COLUMN)) {
                        // The message has been moved to another mailbox
                        Mailbox newMailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
                        if (newMailbox == null) {
                            continue;
                        }
                        copyMessage(serverId, newMailbox);
                    }

                    // We can only send flag changes to the server in 12.0 or later
                    int flag =  currentCursor.getInt(UPDATE_DELETE_FAVORITE_COLUMN);
                    if (flag != updatesCursor.getInt(UPDATE_DELETE_FAVORITE_COLUMN)) {
                        flagChange = true;
                    }

                    int read = currentCursor.getInt(UPDATE_DELETE_READ_COLUMN);
                    if (read != updatesCursor.getInt(UPDATE_DELETE_READ_COLUMN)) {
                        readChange = true;
                    }

                    if (!flagChange && !readChange) {
                        // In this case, we've got nothing to send to the server
                        continue;
                    }

                    ServerUpdate update = new ServerUpdate(id, serverId);
                    if (readChange) {
                        if (read == 1) {
                            mReadUpdates.add(update);
                        } else {
                            mUnreadUpdates.add(update);
                        }
                    }
                    if (flagChange) {
                        if (flag == 1) {
                            mFlaggedUpdates.add(update);
                        } else {
                            mUnflaggedUpdates.add(update);
                        }
                    }
                } finally {
                    currentCursor.close();
                }
            }
        } finally {
            updatesCursor.close();
        }

        if (!mUpdatedIds.isEmpty()) {
            sendUpdate(mReadUpdates, "+FLAGS (\\Seen)");
            sendUpdate(mUnreadUpdates, "-FLAGS (\\Seen)");
            sendUpdate(mFlaggedUpdates, "+FLAGS (\\Flagged)");
            sendUpdate(mUnflaggedUpdates, "-FLAGS (\\Flagged)");
            // Delete the updates now
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            for (Long id: mUpdatedIds) {
                ops.add(ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(Message.UPDATED_CONTENT_URI, id)).build());
            }
            applyBatch(ops);
        }
    }

    private void sendUpdate(Stack<ServerUpdate> updates, String command) throws IOException {
        // First, generate the appropriate String
        while (!updates.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20 && !updates.empty(); i++) {
                ServerUpdate update = updates.pop();
                if (i != 0) {
                    sb.append(',');
                }
                sb.append(update.serverId);
            }
            String tag =
                    writeCommand(mConnection.writer, "uid store " + sb.toString() + " " + command);
            if (!readResponse(mConnection.reader, tag, "STORE").equals(IMAP_OK)) {
                errorLog("Server flag update failed?");
                return;
            }
        }
    }

    private void copyMessage(int serverId, Mailbox mailbox) throws IOException {
        String tag = writeCommand(mConnection.writer, "uid copy " + serverId + " \"" +
                encodeFolderName(mailbox.mServerId) + "\"");
        if (readResponse(mConnection.reader, tag, "COPY").equals(IMAP_OK)) {
            tag = writeCommand(mConnection.writer, "uid store " + serverId + " +FLAGS(\\Deleted)");
            if (readResponse(mConnection.reader, tag, "STORE").equals(IMAP_OK)) {
                tag = writeCommand(mConnection.writer, "expunge");
                readResponse(mConnection.reader, tag, "expunge");
            }
        } else {
            errorLog("Server copy failed?");
        }
    }

    private void saveNewMessages (ArrayList<Message> msgList) {
        //        Cursor dc = getLocalDeletedCursor();
        //        ArrayList<Integer> dl = new ArrayList<Integer>();
        //        boolean newDeletions = false;
        //        try {
        //            if (dc.moveToFirst()) {
        //                do {
        //                    dl.add(dc.getInt(Email.UID_COLUMN));
        //                    newDeletions = true;
        //                } while (dc.moveToNext());
        //            }
        //        } finally {
        //            dc.close();
        //        }

        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        for (Message msg: msgList) {
            //if (newDeletions && dl.contains(msg.uid)) {
            //    userLog("PHEW! Didn't save deleted message with uid: " + msg.uid);
            //    continue;
            //}
            msg.addSaveOps(ops);
        }
        applyBatch(ops);
    }

    private String readTextPart (ImapInputStream in, String tag, Attachment att, boolean lastPart)
            throws IOException {
        String res = in.readLine();

        int bstart = res.indexOf("body[");
        if (bstart < 0)
            bstart = res.indexOf("BODY[");
        if (bstart < 0)
            return "";
        int bend = res.indexOf(']', bstart);
        if (bend < 0)
            return "";

        //String charset = getCharset(thisLoc);
        boolean qp = att.mEncoding.equalsIgnoreCase("quoted-printable");

        int br = res.indexOf('{');
        if (br > 0) {
            Parser p = new Parser(res, br + 1);
            int length = p.parseInteger();
            int len = length;
            byte[] buf = new byte[len];
            int offs = 0;
            while (len > 0) {
                int rd = in.read(buf, offs, len);
                offs += rd;
                len -= rd;
            }

            if (qp) {
                length = QuotedPrintable.decode(buf, length);
            }

            if (lastPart) {
                String line = in.readLine();
                if (!line.endsWith(")")) {
                    userLog("Bad text part?");
                    throw new IOException();
                }
                line = in.readLine();
                if (!line.startsWith(tag)) {
                    userLog("Bad text part?");
                    throw new IOException();
                }
            }
            return new String(buf, 0, length, Charset.forName("UTF8"));

        } else {
            return "";
        }
    }

    private Thread mBodyThread;
    private Connection mConnection;

    private void parseBodystructure (Message msg, Parser p, String level, int cnt,
            ArrayList<Attachment> viewables, ArrayList<Attachment> attachments) {
        if (p.peekChar() == '(') {
            // Multipart variant
            while (true) {
                String ps = p.parseList();
                if (ps == null)
                    break;
                parseBodystructure(msg,
                        new Parser(ps), level + ((level.length() > 0) ? '.' : "") + cnt, 1,
                        viewables, attachments);
                cnt++;
            }
            // Multipart type (MIXED/ALTERNATIVE/RELATED)
            String mp = p.parseString();
            userLog("Multipart: " + mp);
        } else {
            boolean attachment = true;
            String fileName = "";

            // Here's an actual part...
            // mime type
            String type = p.parseString().toLowerCase();
            // mime subtype
            String sub = p.parseString().toLowerCase();
            // parameter list or NIL
            String paramList = p.parseList();
            if (paramList == null)
                p.parseAtom();
            else {
                Parser pp = new Parser(paramList);
                String param;
                while ((param = pp.parseString()) != null) {
                    String val = pp.parseString();
                    if (param.equalsIgnoreCase("name")) {
                        fileName = val;
                    } else if (param.equalsIgnoreCase("charset")) {
                        // TODO: Do we need to handle this?
                    }
                }
            }
            // contentId
            String contentId = p.parseString();
            if (contentId != null) {
                // Must remove the angle-bracket pair
                contentId = contentId.substring(1, contentId.length() - 1);
                fileName = "";
            }

            // contentName
            p.parseString();
            // encoding
            String encoding = p.parseString().toLowerCase();
            // length
            Integer length = p.parseInteger();
            String lvl = level.length() > 0 ? level : String.valueOf(cnt);

            // body MD5
            p.parseStringOrAtom();

            // disposition
            paramList = p.parseList();
            if (paramList != null) {
                //A parenthesized list, consisting of a disposition type
                //string, followed by a parenthesized list of disposition
                //attribute/value pairs as defined in [DISPOSITION].
                Parser pp = new Parser(paramList);
                String param;
                while ((param = pp.parseString()) != null) {
                    String val = pp.parseString();
                    if (param.equalsIgnoreCase("name") || param.equalsIgnoreCase("filename")) {
                        fileName = val;
                    }
                }
            }

            // Don't waste time with Microsoft foolishness
            if (!sub.equals("ms-tnef")) {
                Attachment att = new Attachment();
                att.mLocation = lvl;
                att.mMimeType = type + "/" + sub;
                att.mSize = length;
                att.mFileName = fileName;
                att.mEncoding = encoding;
                att.mContentId = contentId;
                // TODO: charset?

                if ((!type.startsWith("text")) && attachment) {
                    //msg.encoding |= Email.ENCODING_HAS_ATTACHMENTS;
                    attachments.add(att);
                } else {
                    viewables.add(att);
                }

                userLog("Part " + lvl + ": " + type + "/" + sub);
            }

        }
    }

    private void fetchMessageData(Connection conn, Cursor c) throws IOException {
        for (;;) {
            try {
                if (c == null) {
                    c = mResolver.query(Message.CONTENT_URI, Message.CONTENT_PROJECTION,
                            MessageColumns.FLAG_LOADED + "=" + Message.FLAG_LOADED_UNLOADED, null,
                            MessageColumns.TIMESTAMP + " desc");
                    if (c == null || c.getCount() == 0) {
                        return;
                    }
                }
                while (c.moveToNext()) {
                    // Parse the message's bodystructure
                    Message msg = new Message();
                    msg.restore(c);
                    ArrayList<Attachment> viewables = new ArrayList<Attachment>();
                    ArrayList<Attachment> attachments = new ArrayList<Attachment>();
                    parseBodystructure(msg, new Parser(msg.mSyncData), "", 1, viewables,
                            attachments);
                    ContentValues values = new ContentValues();
                    values.put(MessageColumns.FLAG_LOADED, Message.FLAG_LOADED_COMPLETE);
                    // Save the attachments...
                    for (Attachment att: attachments) {
                        att.mAccountKey = mAccount.mId;
                        att.mMessageKey = msg.mId;
                        att.save(mContext);
                    }
                    // Whether or not we have attachments
                    values.put(MessageColumns.FLAG_ATTACHMENT, !attachments.isEmpty());
                    // Get the viewables
                    Attachment textViewable = null;
                    for (Attachment viewable: viewables) {
                        String mimeType = viewable.mMimeType;
                        if ("text/html".equalsIgnoreCase(mimeType)) {
                            textViewable = viewable;
                        } else if ("text/plain".equalsIgnoreCase(mimeType) &&
                                textViewable == null) {
                            textViewable = viewable;
                        }
                    }
                    if (textViewable != null) {
                        // For now, just get single viewable
                        String tag = writeCommand(conn.writer,
                                "uid fetch " + msg.mServerId + " body.peek[" +
                                        textViewable.mLocation + "]<0.200000>");
                        String text = readTextPart(conn.reader, tag, textViewable, true);
                        userLog("Viewable " + textViewable.mMimeType + ", len = " + text.length());
                        // Save it away
                        Body body = new Body();
                        if (textViewable.mMimeType.equalsIgnoreCase("text/html")) {
                            body.mHtmlContent = text;
                        } else {
                            body.mTextContent = text;
                        }
                        body.mMessageKey = msg.mId;
                        body.save(mContext);
                        values.put(MessageColumns.SNIPPET,
                                TextUtilities.makeSnippetFromHtmlText(text));
                    } else {
                        userLog("No viewable?");
                        values.putNull(MessageColumns.SNIPPET);
                    }
                    mResolver.update(ContentUris.withAppendedId(
                            Message.CONTENT_URI, msg.mId), values, null, null);
                }
            } finally {
                if (c != null) {
                    c.close();
                    c = null;
                }
            }
        }
    }

    private void fetchMessageData () throws IOException {
        // If we're already loading messages on another thread, there's nothing to do
        if (mBodyThread != null) return;
        HostAuth hostAuth =
                HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        if (hostAuth == null) return;
        // Find messages to load, if any
        final Cursor unloaded = mResolver.query(Message.CONTENT_URI, Message.CONTENT_PROJECTION,
                MessageColumns.FLAG_LOADED + "=" + Message.FLAG_LOADED_UNLOADED, null,
                MessageColumns.TIMESTAMP + " desc");
        int cnt = unloaded.getCount();
        // If there aren't any, we're done
        if (cnt > 0) {
            userLog("Found " + unloaded.getCount() + " messages requiring fetch");
            // If we have more than one, try a second thread
            // Some servers may not allow this, so we fall back to loading text on the main thread
            if (cnt > 1) {
                final Connection conn = connectAndLogin(hostAuth, "body");
                if (conn.status == EXIT_DONE) {
                    mBodyThread =
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        fetchMessageData(conn, unloaded);
                                        conn.socket.close();
                                    } catch (IOException e) {
                                    } finally {
                                        mBodyThread = null;
                                    }
                                }});
                    mBodyThread.start();
                } else {
                    fetchMessageData(mConnection, unloaded);
                }
            } else {
                fetchMessageData(mConnection, unloaded);
            }
        }
    }

    void readFolderList () throws IOException {
        String tag = writeCommand(mWriter, "list \"\" *");
        String line;
        char dchar = '/';

        userLog("Loading folder list...");

        ArrayList<String> parentList = new ArrayList<String>();
        ArrayList<Mailbox> mailboxList = new ArrayList<Mailbox>();
        while (true) {
            line = mReader.readLine();
            userLog(line);
            if (line.startsWith(tag)) {
                // Done reading folder list
                break;
            } else {
                Parser p = new Parser(line, 2);
                String cmd = p.parseAtom();
                if (cmd.equalsIgnoreCase("list")) {
                    @SuppressWarnings("unused")
                    String props = p.parseListOrNil();
                    String delim = p.parseString();
                    if (delim == null)
                        delim = "~";
                    if (delim.length() == 1)
                        dchar = delim.charAt(0);
                    String serverId = p.parseStringOrAtom();
                    int lastDelim = serverId.lastIndexOf(delim);
                    String displayName;
                    String parentName;
                    if (lastDelim > 0) {
                        displayName = serverId.substring(lastDelim + 1);
                        parentName = serverId.substring(0, lastDelim);
                    } else {
                        displayName = serverId;
                        parentName = null;

                    }
                    Mailbox m = new Mailbox();
                    m.mDisplayName = displayName;
                    m.mAccountKey = mAccount.mId;
                    m.mServerId = serverId;
                    if (parentName != null && !parentList.contains(parentName)) {
                        parentList.add(parentName);
                    }
                    m.mFlagVisible = true;
                    m.mParentServerId = parentName;
                    m.mDelimiter = dchar;
                    m.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
                    mailboxList.add(m);
                } else {
                    // WTF
                }
            }
        }

        // TODO: Use narrower projection
        Cursor c = mResolver.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                Mailbox.ACCOUNT_KEY + "=?", new String[] {Long.toString(mAccount.mId)},
                MailboxColumns.SERVER_ID + " asc");
        if (c == null) return;
        int cnt = c.getCount();
        String[] serverIds = new String[cnt];
        long[] uidvals = new long[cnt];
        long[] ids = new long[cnt];
        int i = 0;

        try {
            if (c.moveToFirst()) {
                // Get arrays of information about existing mailboxes in account
                do {
                    serverIds[i] = c.getString(Mailbox.CONTENT_SERVER_ID_COLUMN);
                    uidvals[i] = c.getLong(Mailbox.CONTENT_SYNC_KEY_COLUMN);
                    ids[i] = c.getLong(Mailbox.CONTENT_ID_COLUMN);
                    i++;
                } while (c.moveToNext());
            }
        } finally {
            c.close();
        }

        ArrayList<Mailbox> addList = new ArrayList<Mailbox>();

        for (Mailbox m: mailboxList) {
            int loc = Arrays.binarySearch(serverIds, m.mServerId);
            if (loc >= 0) {
                // It exists
                if (uidvals[loc] == 0) {
                    // Good enough; a match that we've never visited!
                    // Mark this as touched (-1)...
                    uidvals[loc] = -1;
                } else {
                    // Ok, now we need to see if this is the SAME mailbox...
                    // For now, assume it is; move on
                    // TODO: There's a problem if you've 1) visited this box and 2) another box now
                    // has its name, but how likely is that??
                    uidvals[loc] = -1;
                }
            } else {
                // We don't know about this mailbox, so we'll add it...
                // BUT must see if it's a rename of one we've visited!
                addList.add(m);
            }
        }

        // TODO: Flush this list every N (100?) in case there are zillions
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        try {
            for (i = 0; i < cnt; i++) {
                String name = serverIds[i];
                long uidval = uidvals[i];
                // -1 means matched; ignore
                // 0 means unmatched and never before seen
                // > 0 means unmatched and HAS been seen.  must find mWriter why
                // TODO: Get rid of "Outbox"
                if (uidval == 0 && !name.equals("Outbox") &&
                        !name.equalsIgnoreCase(INBOX_SERVER_NAME)) {
                    // Ok, here's one we've never visited and it's not in the new list
                    ops.add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(
                            Mailbox.CONTENT_URI, ids[i])).build());
                    userLog("Deleting unseen mailbox; no match: " + name);
                } else if (uidval > 0 && !name.equalsIgnoreCase(INBOX_SERVER_NAME)) {
                    boolean found = false;
                    for (Mailbox m : addList) {
                        tag = writeCommand(mWriter, "status \"" + m.mServerId + "\" (UIDVALIDITY)");
                        if (readResponse(mReader, tag, "STATUS").equals(IMAP_OK)) {
                            String str = mImapResponse.get(0).toLowerCase();
                            int idx = str.indexOf("uidvalidity");
                            long num = readLong(str, idx + 12);
                            if (uidval == num) {
//                                try {
//                                    // This is a renamed mailbox...
//                                    c = Mailbox.getCursorWhere(mDatabase, "account=" + mAccount.id + " and serverName=?",   name);
//                                    if (c != null && c.moveToFirst()) {
//                                        Mailbox existing = Mailbox.restoreFromCursor(c);
//                                        userLog("Renaming existing mailbox: " + existing.mServerId + " to: " + m.mServerId);
//                                        existing.mDisplayName = m.mDisplayName;
//                                        existing.mServerId = m.mServerId;
//                                        m.mHierarchicalName = m.mServerId;
//                                        existing.mParentServerId = m.mParentServerId;
//                                        existing.mFlags = m.mFlags;
//                                        existing.save(mDatabase);
//                                        // Mark this so that we don't save it below
//                                        m.mServerId = null;
//                                    }
//                                } finally {
//                                    if (c != null) {
//                                        c.close();
//                                    }
//                                }
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found) {
                        // There's no current mailbox with this uidval, so delete.
                        ops.add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(
                                Mailbox.CONTENT_URI, ids[i])).build());
                        userLog("Deleting uidval mailbox; no match: " + name);
                    }
                }
            }
            for (Mailbox m : addList) {
                String serverId = m.mServerId;
                if (serverId == null)
                    continue;
                if (!serverId.equalsIgnoreCase(INBOX_SERVER_NAME)
                        && !serverId.equalsIgnoreCase("Outbox")) {
                    m.mHierarchicalName = m.mServerId;
                    //*** For now, use Mail.  We need a way to select the others...
                    m.mType = Mailbox.TYPE_MAIL;
                    ops.add(ContentProviderOperation.newInsert(
                            Mailbox.CONTENT_URI).withValues(m.toContentValues()).build());
                    userLog("Adding new mailbox: " + m.mServerId);
                }
            }

            applyBatch(ops);
            // Fixup parent stuff, flags...
            MailboxUtilities.fixupUninitializedParentKeys(mContext,
                    Mailbox.ACCOUNT_KEY + "=" + mAccount.mId);
        } finally {
            SyncManager.kick("folder list");
        }
        // TODO: Make sure UI is updated
    }

    public int getDepth (String name, char delim) {
        int depth = 0;
        int last = -1;
        while (true) {
            last = name.indexOf(delim, last + 1);
            if (last < 0)
                return depth;
            depth++;
        }
    }


    private void applyBatch(ArrayList<ContentProviderOperation> ops) {
        try {
            mResolver.applyBatch(EmailContent.AUTHORITY, ops);
        } catch (RemoteException e) {
            // Nothing to be done
        } catch (OperationApplicationException e) {
            // These operations are legal; this can't really happen
        }
    }

    private void processServerDeletes(ArrayList<Integer> deleteList) {
        int cnt = deleteList.size();
        if (cnt > 0) {
            ArrayList<ContentProviderOperation> ops =
                    new ArrayList<ContentProviderOperation>();
            for (int i = 0; i < cnt; i++) {
                MAILBOX_SERVER_ID_ARGS[1] = Long.toString(deleteList.get(i));
                Builder b = ContentProviderOperation.newDelete(
                        Message.SYNCED_SELECTION_CONTENT_URI);
                b.withSelection(MessageColumns.MAILBOX_KEY + "=? AND " +
                        SyncColumns.SERVER_ID + "=?", MAILBOX_SERVER_ID_ARGS);
                ops.add(b.build());
            }
            applyBatch(ops);
        }
    }

    private void processServerUpdates(ArrayList<Integer> deleteList, ContentValues values) {
        int cnt = deleteList.size();
        if (cnt > 0) {
            ArrayList<ContentProviderOperation> ops =
                    new ArrayList<ContentProviderOperation>();
            for (int i = 0; i < cnt; i++) {
                MAILBOX_SERVER_ID_ARGS[1] = Long.toString(deleteList.get(i));
                Builder b = ContentProviderOperation.newUpdate(
                        Message.SYNCED_SELECTION_CONTENT_URI);
                b.withSelection(MessageColumns.MAILBOX_KEY + "=? AND " +
                        SyncColumns.SERVER_ID + "=?", MAILBOX_SERVER_ID_ARGS);
                b.withValues(values);
                ops.add(b.build());
            }
            applyBatch(ops);
        }
    }

    private static class Reconciled {
        ArrayList<Integer> insert;
        ArrayList<Integer> delete;

        Reconciled (ArrayList<Integer> ins, ArrayList<Integer> del) {
            insert = ins;
            delete = del;
        }
    }

    // Arrays must be sorted beforehand
    public Reconciled reconcile (String name, int[] deviceList, int[] serverList) {
        ArrayList<Integer> loadList = new ArrayList<Integer>();
        ArrayList<Integer> deleteList = new ArrayList<Integer>();
        int soff = 0;
        int doff = 0;
        int scnt = serverList.length;
        int dcnt = deviceList.length;

        while (scnt > 0 || dcnt > 0) {
            if (scnt == 0) {
                for (; dcnt > 0; dcnt--)
                    deleteList.add(deviceList[doff++]);
                break;
            } else if (dcnt == 0) {
                for (; scnt > 0; scnt--)
                    loadList.add(serverList[soff++]);
                break;
            }
            int s = serverList[soff++];
            int d = deviceList[doff++];
            scnt--;
            dcnt--;
            if (s == d) {
                continue;
            } else if (s > d) {
                deleteList.add(d);
                scnt++;
                soff--;
            } else if (d > s) {
                loadList.add(s);
                dcnt++;
                doff--;
            }
        }

        userLog("Reconciler " + name + "-> Insert: " + loadList.size() +
                ", Delete: " + deleteList.size());
        return new Reconciled(loadList, deleteList);
    }

    private static final String[] UID_PROJECTION = new String[] {SyncColumns.SERVER_ID};
    public int[] getUidList (String andClause) {
        int offs = 0;
        String ac = MessageColumns.MAILBOX_KEY + "=?";
        if (andClause != null) {
            ac = ac + andClause;
        }
        Cursor c = mResolver.query(Message.CONTENT_URI, UID_PROJECTION,
                ac, new String[] {Long.toString(mMailboxId)}, SyncColumns.SERVER_ID);
        if (c != null) {
            try {
                int[] uids = new int[c.getCount()];
                if (c.moveToFirst()) {
                    do {
                        uids[offs++] = c.getInt(0);
                    } while (c.moveToNext());
                    return uids;
                }
            } finally {
                c.close();
            }
        }
        return new int[0];
    }

    public int[] getUnreadUidList () {
        return getUidList(" and " + Message.FLAG_READ + "=0");
    }

    public int[] getFlaggedUidList () {
        return getUidList(" and " + Message.FLAG_FAVORITE + "!=0");
    }

    private void reconcileState(int[] deviceList, String since, String flag, String search,
            String column, boolean sense) throws IOException {
        int[] serverList;
        Parser p;
        String msgs;
        String tag = writeCommand(mWriter, "uid search undeleted " + search + " since " + since);
        if (readResponse(mReader, tag, "SEARCH").equals(IMAP_OK)) {
            if (mImapResponse.isEmpty()) {
                serverList = new int[0];
            } else {
                msgs = mImapResponse.get(0);
                p = new Parser(msgs, 8);
                serverList = p.gatherInts();
                Arrays.sort(serverList);
            }
            Reconciled r = reconcile(flag, deviceList, serverList);
            ContentValues values = new ContentValues();
            values.put(column, sense);
            processServerUpdates(r.delete, values);
            values.put(column, !sense);
            processServerUpdates(r.insert, values);
        }
    }

    private ArrayList<String> getTokens(String str) {
        ArrayList<String> tokens = new ArrayList<String>();
        Parser p = new Parser(str);
        while(true) {
            String capa = p.parseAtom();
            if (capa == null) {
                break;
            }
            tokens.add(capa);
        }
        return tokens;
    }

    public static class Connection {
        Socket socket;
        int status;
        ImapInputStream reader;
        BufferedWriter writer;
    }

    private String mUserAgent;

    private Connection connectAndLogin(HostAuth hostAuth, String name) {
        Connection conn = new Connection();
        Socket socket;
        try {
            socket = getSocket(hostAuth);
            socket.setSoTimeout(SOCKET_TIMEOUT);
            userLog(">>> IMAP CONNECTION SUCCESSFUL: " + name);

            ImapInputStream reader = new ImapInputStream(socket.getInputStream());
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream()));
            // Get welcome string
            reader.readLine();

            String tag = writeCommand(writer, "CAPABILITY");
            if (readResponse(reader, tag, "CAPABILITY").equals(IMAP_OK)) {
                // If CAPABILITY
                if (!mImapResponse.isEmpty()) {
                    String capa = mImapResponse.get(0).toLowerCase();
                    ArrayList<String> tokens = getTokens(capa);
                    if (tokens.contains("starttls")) {
                        // Handle STARTTLS
                        userLog("[Supports STARTTLS]");
                    }
                    if (tokens.contains("id")) {
                        String hostAddress = hostAuth.mAddress;
                        // Never send ID to *.secureserver.net
                        // Hackish, yes, but we've been doing this for years... :-(
                        if (!hostAddress.toLowerCase().endsWith(".secureserver.net")) {
                            // Assign user-agent string (for RFC2971 ID command)
                            if (mUserAgent == null) {
                                mUserAgent = ImapId.getImapId(mContext, hostAuth.mLogin,
                                        hostAddress, null);
                            }
                            tag = writeCommand(writer, "ID (" + mUserAgent + ")");
                            // We learn nothing useful from the response
                            readResponse(reader, tag);
                        }
                    }
                }
            }

            tag = writeCommand(writer,
                    "login " + hostAuth.mLogin + ' ' + hostAuth.mPassword);
            if (!readResponse(reader, tag).equals(IMAP_OK)) {
                conn.status = EXIT_LOGIN_FAILURE;
            } else {
                conn.socket = socket;
                conn.reader = reader;
                conn.writer = writer;
                conn.status = EXIT_DONE;
                userLog(">>> LOGGED IN: " + name);
                if (mMailboxName != null) {
                    tag = writeCommand(conn.writer, "select \"" + encodeFolderName(mMailboxName) +
                            '\"');
                    if (!readResponse(conn.reader, tag).equals(IMAP_OK)) {
                        // Select failed
                        userLog("Select failed?");
                        conn.status = EXIT_EXCEPTION;
                    } else {
                        userLog(">>> SELECTED");
                    }
                }
            }
        } catch (CertificateValidationException e) {
            conn.status = EXIT_LOGIN_FAILURE;
        } catch (IOException e) {
            conn.status = EXIT_IO_ERROR;
        }
        return conn;
    }

    private void setMailboxSyncStatus(long id, int status) {
        ContentValues values = new ContentValues();
        values.put(Mailbox.UI_SYNC_STATUS, status);
        // Make sure we're always showing a "success" value.  A failure wouldn't get set here, but
        // rather via SyncService.done()
        values.put(Mailbox.UI_LAST_SYNC_RESULT, Mailbox.LAST_SYNC_RESULT_SUCCESS);
        mResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, id), values, null, null);
    }

    private void idle() throws IOException {
        mIsIdle = true;
        mThread.setName(mMailboxName + ":IDLE[" + mAccount.mDisplayName + "]");
        userLog("Entering idle...");
        String tag = writeCommand(mWriter, "idle");

        try {
            while (true) {
                String resp = mReader.readLine();
                if (resp.startsWith("+"))
                    break;
                // Remember to handle untagged responses here (sigh, and elsewhere)
                if (resp.startsWith("* ")) 
                    handleUntagged(resp);
                else {
                    userLog("Error in IDLE response: " + resp);
                    //*** How to handle this?
                    return;
                }
            }

            // Server has accepted IDLE
            long idleStartTime = System.currentTimeMillis();

            // Let the socket time out a minute after we expect to terminate it ourselves
            mSocket.setSoTimeout(IDLE_ASLEEP_MILLIS + (1*MINS));
            // Say we're no longer syncing (turn off indeterminate progress in the UI)
            setMailboxSyncStatus(mMailboxId, UIProvider.SyncStatus.NO_SYNC);
            // Set an alarm for one minute before our timeout our expected IDLE time
            Imap2SyncManager.runAsleep(mMailboxId, IDLE_ASLEEP_MILLIS);

            while (true) {
                String line = null;
                try {
                    line = mReader.readLine();
                    userLog(line);
                } catch (SocketTimeoutException e) {
                    userLog("Socket timeout");
                } finally {
                    Imap2SyncManager.runAwake(mMailboxId);
                    // Say we're syncing again
                    setMailboxSyncStatus(mMailboxId, UIProvider.SyncStatus.BACKGROUND_SYNC);
                }
                if (line == null || line.startsWith("* ")) {
                    boolean finish = (line == null) ? true : handleUntagged(line);
                    if (!finish) {
                        long timeSinceIdle =
                                System.currentTimeMillis() - idleStartTime;
                        // If we're nearing the end of IDLE time, let's just reset the IDLE while
                        // we've got the processor awake
                        if (timeSinceIdle > IDLE_ASLEEP_MILLIS - (2*MINS)) {
                            userLog("Time to reset IDLE...");
                            finish = true;
                        }
                    }
                    if (finish) {
                        mWriter.write("DONE\r\n");
                        mWriter.flush();
                    }
                } else if (line.startsWith(tag)) {
                    Parser p = new Parser(line, tag.length() - 1);
                    mImapResult = p.parseAtom();
                    mIsIdle = false;
                    break;
                }
            }
        } finally {
            // We might have left IDLE due to an exception
            if (mSocket != null) {
                // Reset the standard timeout
                mSocket.setSoTimeout(20 * 1000);
            }
            mIsIdle = false;
            mThread.setName(mMailboxName + "[" + mAccount.mDisplayName + "]");
        }
    }

    // Upload sent messages to server

    //    void foo() {
    //        Cursor c = ServerUploads.getCursorWhere(mDatabase, "account=" + mAccount.id);
    //        ArrayList<Long> uploaded = new ArrayList<Long>();
    //        try {
    //            if (c.moveToFirst()) {
    //                do{
    //                    Mailbox m = Mailbox.restoreFromId(mDatabase, c.getLong(ServerUploads.TO_MAILBOX_COLUMN));
    //                    String fn = c.getString(ServerUploads.FILENAME_COLUMN);
    //                    if (m != null) {
    //                        FileInputStream fi = null;
    //                        try {
    //                            fi = mContext.openFileInput(fn);
    //                        } catch (Exception e) {
    //                            logException(e);
    //                        }
    //                        if (fi != null) {
    //                            BufferedInputStream bin = new BufferedInputStream(fi);
    //                            mWriter.flush();
    //                            BufferedOutputStream bos = new BufferedOutputStream(mSocket.getOutputStream());
    //                            int len = fi.available();
    //                            byte[] buf;
    //                            try {
    //                                tag = writeCommand(mWriter, "append \"" + m.serverName + "\" (\\seen) {" + len + '}');
    //                                String line = in.readLine();
    //                                buf = new byte[CHUNK_SIZE];
    //                                if (line.startsWith("+")) {
    //                                    userLog("append response: " + line);
    //
    //                                    while (len > 0) {
    //                                        int rlen = (len > CHUNK_SIZE) ? CHUNK_SIZE : len;
    //                                        int rd = bin.read(buf, 0, rlen);
    //                                        if (rd > 0) {
    //                                            bos.write(buf, 0, rd);
    //                                        } else if (rd < 0)
    //                                            break;
    //                                        len -= rd;
    //                                    }
    //
    //                                    bos.flush();
    //                                    mWriter.write("\r\n");
    //                                    mWriter.flush();
    //                                    bin.close();
    //                                    if (readResponse(in, tag).equals(IMAP_OK)) {
    //                                        uploaded.add(c.getLong(ServerUploads.ID_COLUMN));
    //                                        File f = mContext.getFileStreamPath(fn);
    //                                        if (f.delete()) {
    //                                            userLog("Upload file deleted: " + fn);
    //                                        }
    //                                    } else {
    //                                        userLog("Append failed?");
    //                                        uploaded.add(c.getLong(ServerUploads.ID_COLUMN));
    //                                    }
    //                                } else {
    //                                    userLog("Append failed: " + line);
    //                                    uploaded.add(c.getLong(ServerUploads.ID_COLUMN));
    //                                }
    //                            } catch (Exception e) {
    //                                logException(e);
    //                                uploaded.add(c.getLong(ServerUploads.ID_COLUMN));
    //                            }
    //
    //                            buf = null;
    //                            if (m.name.equals(Mailbox.DRAFTS_NAME))
    //                                MailService.serviceRequest(m.id, 3000L);
    //                        } else {
    //                            userLog("Can't find file to upload, deleting upload record: " + fn);
    //                            uploaded.add(c.getLong(ServerUploads.ID_COLUMN));
    //                        }
    //                    }
    //                } while (c.moveToNext());
    //            }
    //        } finally {
    //            // Delete the upload records for those completed
    //            for (Long id: uploaded) {
    //                ServerUploads.deleteById(mDatabase, id);
    //            }
    //            c.close();
    //        }
    //    }

    private void processRequests() throws IOException {
         while (!mRequestQueue.isEmpty()) {
            Request req = mRequestQueue.peek();

            // Our two request types are PartRequest (loading attachment) and
            // MeetingResponseRequest (respond to a meeting request)
            if (req instanceof PartRequest) {
                TrafficStats.setThreadStatsTag(
                        TrafficFlags.getAttachmentFlags(mContext, mAccount));
                new AttachmentLoader(this,
                        (PartRequest)req).loadAttachment(mConnection);
                TrafficStats.setThreadStatsTag(
                        TrafficFlags.getSyncFlags(mContext, mAccount));
            }

            // If there's an exception handling the request, we'll throw it
            // Otherwise, we remove the request
            mRequestQueue.remove();
        }
    }

    private void sync () throws IOException {
        mThread = Thread.currentThread();

        HostAuth hostAuth =
                HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
        if (hostAuth == null) return;
        Connection conn = connectAndLogin(hostAuth, "main");
        if (conn.status != EXIT_DONE) {
            mExitStatus = conn.status;
            return;
        }
        setConnection(conn);

        // The account might have changed!!
        //*** Determine how to often to do this
        if (mMailboxName.equalsIgnoreCase("inbox")) {
            long startTime = System.currentTimeMillis();
            readFolderList();
            userLog("Folder list processed in " + (System.currentTimeMillis() - startTime) +
                    "ms");
        }

        while (!mStop) {
            try {
                while (!mStop) {
                    mIsServiceRequestPending = false;

                    // Now, handle various requests
                    processRequests();

                    long days;
                    if (mMailbox.mSyncLookback == SyncWindow.SYNC_WINDOW_UNKNOWN) {
                        days = 14;
                    } else
                        days = SyncWindow.toDays(mMailbox.mSyncLookback);

                    long time = System.currentTimeMillis() - (days*DAYS);
                    Date date = new Date(time);
                    String since = IMAP_DATE_FORMAT.format(date);
                    String tag = writeCommand(mWriter, "uid search undeleted since " + since);

                    // TODO Handle multi-line search result (google)
                    if (!readResponse(mReader, tag, "SEARCH").equals(IMAP_OK)) {
                        userLog("$$$ WHOA!   Search failed? ");
                    }

                    userLog(">>> SEARCH RESULT");
                    int[] serverList;
                    String msgs;
                    Parser p;
                    if (mImapResponse.isEmpty()) {
                        serverList = new int[0];
                    } else {
                        msgs = mImapResponse.get(0);
                        //*** Magic number?
                        p = new Parser(msgs, 8);
                        serverList = p.gatherInts();
                    }

                    Arrays.sort(serverList);
                    int[] deviceList = getUidList(null);
                    Reconciled r =
                            reconcile("MESSAGES", deviceList, serverList);
                    ArrayList<Integer> loadList = r.insert;
                    ArrayList<Integer> deleteList = r.delete;
                    serverList = null;
                    deviceList = null;
                    int cnt = loadList.size();

                    // We load message headers 20 at a time at this point...
                    int idx= 1;
                    boolean loadedSome = false;
                    while (idx <= cnt) {
                        ArrayList<Message> tmsgList = new ArrayList<Message> ();
                        int tcnt = 0;
                        StringBuilder tsb = new StringBuilder("uid fetch ");
                        for (tcnt = 0; tcnt < HEADER_BATCH_COUNT && idx <= cnt; tcnt++, idx++) {
                            // Load most recent first
                            if (tcnt > 0)
                                tsb.append(',');
                            tsb.append(loadList.get(cnt - idx));
                        }
                        tsb.append(" (uid internaldate flags envelope bodystructure)");
                        tag = writeCommand(mWriter, tsb.toString());
                        if (readResponse(mReader, tag, "FETCH").equals(IMAP_OK)) {
                            // Create message and store
                            for (int j = 0; j < tcnt; j++) {
                                Message msg = createMessage(mImapResponse.get(j));
                                tmsgList.add(msg);
                            }
                            saveNewMessages(tmsgList);
                        }

                        fetchMessageData();
                        loadedSome = true;
                    }   
                    // TODO: Use loader to watch for changes on unloaded body cursor
                    if (!loadedSome) {
                        fetchMessageData();
                    }

                    // Reflect server deletions on device; do them all at once
                    processServerDeletes(deleteList);

                    handleLocalUpdates();

                    handleLocalDeletes();

                    reconcileState(getUnreadUidList(), since, "UNREAD", "unseen",
                            MessageColumns.FLAG_READ, true);
                    reconcileState(getFlaggedUidList(), since, "FLAGGED", "flagged",
                            MessageColumns.FLAG_FAVORITE, false);

                    // We're done if not pushing...
                    if (mMailbox.mSyncInterval != Mailbox.CHECK_INTERVAL_PUSH) {
                        mExitStatus = EXIT_DONE;
                        return;
                    }

                    // If new requests have come in, process them
                    if (mIsServiceRequestPending)
                        continue;

                    idle();
                }

            } finally {
                if (mSocket != null) {
                    try {
                        mSocket.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            // If we've been stopped, we're done
            if (mStop) return;

            // Whether or not we're the account mailbox
            try {
                if ((mMailbox == null) || (mAccount == null)) {
                    return;
                } else {
                    int trafficFlags = TrafficFlags.getSyncFlags(mContext, mAccount);
                    TrafficStats.setThreadStatsTag(trafficFlags | TrafficFlags.DATA_EMAIL);

                    // We loop because someone might have put a request in while we were syncing
                    // and we've missed that opportunity...
                    do {
                        if (mRequestTime != 0) {
                            userLog("Looping for user request...");
                            mRequestTime = 0;
                        }
                        if (mSyncReason >= Imap2SyncManager.SYNC_CALLBACK_START) {
                            try {
                                Imap2SyncManager.callback().syncMailboxStatus(mMailboxId,
                                        EmailServiceStatus.IN_PROGRESS, 0);
                            } catch (RemoteException e1) {
                                // Don't care if this fails
                            }
                        }
                        sync();
                    } while (mRequestTime != 0);
                }
            } catch (IOException e) {
                String message = e.getMessage();
                userLog("Caught IOException: ", (message == null) ? "No message" : message);
                mExitStatus = EXIT_IO_ERROR;
            } catch (Exception e) {
                userLog("Uncaught exception in EasSyncService", e);
            } finally {
                int status;
                Imap2SyncManager.done(this);
                if (!mStop) {
                    userLog("Sync finished");
                    switch (mExitStatus) {
                        case EXIT_IO_ERROR:
                            status = EmailServiceStatus.CONNECTION_ERROR;
                            break;
                        case EXIT_DONE:
                            status = EmailServiceStatus.SUCCESS;
                            ContentValues cv = new ContentValues();
                            cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
                            String s = "S" + mSyncReason + ':' + status + ':' + mChangeCount;
                            cv.put(Mailbox.SYNC_STATUS, s);
                            mContext.getContentResolver().update(
                                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mMailboxId),
                                    cv, null, null);
                            break;
                        case EXIT_LOGIN_FAILURE:
                            status = EmailServiceStatus.LOGIN_FAILED;
                            break;
                        default:
                            status = EmailServiceStatus.REMOTE_EXCEPTION;
                            errorLog("Sync ended due to an exception.");
                            break;
                    }
                } else {
                    userLog("Stopped sync finished.");
                    status = EmailServiceStatus.SUCCESS;
                }

                // Send a callback (doesn't matter how the sync was started)
                try {
                    // Unless the user specifically asked for a sync, we don't want to report
                    // connection issues, as they are likely to be transient.  In this case, we
                    // simply report success, so that the progress indicator terminates without
                    // putting up an error banner
                    //***
                    if (mSyncReason != Imap2SyncManager.SYNC_UI_REQUEST &&
                            status == EmailServiceStatus.CONNECTION_ERROR) {
                        status = EmailServiceStatus.SUCCESS;
                    }
                    Imap2SyncManager.callback().syncMailboxStatus(mMailboxId, status, 0);
                } catch (RemoteException e1) {
                    // Don't care if this fails
                }

                // Make sure ExchangeService knows about this
                Imap2SyncManager.kick("sync finished");
            }
        } catch (ProviderUnavailableException e) {
            Log.e(TAG, "EmailProvider unavailable; sync ended prematurely");
        }
    }

    private Socket getSocket(HostAuth hostAuth) throws CertificateValidationException, IOException {
        Socket socket;
        try {
            boolean ssl = (hostAuth.mFlags & HostAuth.FLAG_SSL) != 0;
            boolean trust = (hostAuth.mFlags & HostAuth.FLAG_TRUST_ALL) != 0;
            SocketAddress socketAddress = new InetSocketAddress(hostAuth.mAddress, hostAuth.mPort);
            if (ssl) {
                socket = SSLUtils.getSSLSocketFactory(trust).createSocket();
            } else {
                socket = new Socket();
            }
            socket.connect(socketAddress, SOCKET_CONNECT_TIMEOUT);
            // After the socket connects to an SSL server, confirm that the hostname is as expected
            if (ssl && !trust) {
                verifyHostname(socket, hostAuth.mAddress);
            }
        } catch (SSLException e) {
            errorLog(e.toString());
            throw new CertificateValidationException(e.getMessage(), e);
        }
        return socket;
    }

    /**
     * Lightweight version of SSLCertificateSocketFactory.verifyHostname, which provides this
     * service but is not in the public API.
     *
     * Verify the hostname of the certificate used by the other end of a
     * connected socket.  You MUST call this if you did not supply a hostname
     * to SSLCertificateSocketFactory.createSocket().  It is harmless to call this method
     * redundantly if the hostname has already been verified.
     *
     * <p>Wildcard certificates are allowed to verify any matching hostname,
     * so "foo.bar.example.com" is verified if the peer has a certificate
     * for "*.example.com".
     *
     * @param socket An SSL socket which has been connected to a server
     * @param hostname The expected hostname of the remote server
     * @throws IOException if something goes wrong handshaking with the server
     * @throws SSLPeerUnverifiedException if the server cannot prove its identity
     */
    private void verifyHostname(Socket socket, String hostname) throws IOException {
        // The code at the start of OpenSSLSocketImpl.startHandshake()
        // ensures that the call is idempotent, so we can safely call it.
        SSLSocket ssl = (SSLSocket) socket;
        ssl.startHandshake();

        SSLSession session = ssl.getSession();
        if (session == null) {
            throw new SSLException("Cannot verify SSL socket without session");
        }
        // TODO: Instead of reporting the name of the server we think we're connecting to,
        // we should be reporting the bad name in the certificate.  Unfortunately this is buried
        // in the verifier code and is not available in the verifier API, and extracting the
        // CN & alts is beyond the scope of this patch.
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)) {
            throw new SSLPeerUnverifiedException(
                    "Certificate hostname not useable for server: " + hostname);
        }
    }
}
