package org.jgroups.protocols.raft;

import net.jcip.annotations.GuardedBy;
import org.jgroups.*;
import org.jgroups.annotations.MBean;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.ManagedOperation;
import org.jgroups.annotations.Property;
import org.jgroups.conf.AttributeType;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.raft.util.CommitTable;
import org.jgroups.raft.util.RequestTable;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Bits;
import org.jgroups.util.*;

import java.io.*;
import java.lang.reflect.Array;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Implementation of the <a href="https://github.com/ongardie/dissertation">RAFT consensus protocol</a> in JGroups<p/>
 * [1] https://github.com/ongardie/dissertation
 * @author Bela Ban
 * @since  0.1
 */
@MBean(description="Implementation of the RAFT consensus protocol")
public class RAFT extends Protocol implements Runnable, Settable, DynamicMembership {
    // When moving to JGroups -> add to jg-protocol-ids.xml
    public static final byte[] raft_id_key             = Util.stringToBytes("raft-id");
    protected static final short  RAFT_ID              = 521;

    protected static final Function<ExtendedUUID,String> print_function=uuid -> {
        byte[] val=uuid.get(raft_id_key);
        return val != null? Util.bytesToString(val) : uuid.print();
    };

    // When moving to JGroups -> add to jg-magic-map.xml
    protected static final short  APPEND_ENTRIES_REQ   = 2000;
    protected static final short  APPEND_ENTRIES_RSP   = 2001;
    protected static final short  APPEND_RESULT        = 2002;
    protected static final short  INSTALL_SNAPSHOT_REQ = 2003;

    static {
        ClassConfigurator.addProtocol(RAFT_ID, RAFT.class);
        ClassConfigurator.add(APPEND_ENTRIES_REQ,   AppendEntriesRequest.class);
        ClassConfigurator.add(APPEND_ENTRIES_RSP,   AppendEntriesResponse.class);
        ClassConfigurator.add(INSTALL_SNAPSHOT_REQ, InstallSnapshotRequest.class);
        ClassConfigurator.add(APPEND_RESULT,        AppendResult.class);
    }

    @Property(description="The identifier of this node. Needs to be unique and an element of members. Must not be null",
              writable=false)
    protected String                  raft_id;

    /** The set of members defining the Raft cluster */
    @GuardedBy("members")
    protected final List<String>      members=new ArrayList<>();

    @ManagedAttribute(description="Majority needed to achieve consensus; computed from members)")
    @GuardedBy("members")
    protected int                     majority=-1;

    @ManagedAttribute(description="If true, we can change 'members' at runtime")
    protected boolean                 dynamic_view_changes=true;


    @Property(description="The fully qualified name of the class implementing Log")
    protected String                  log_class="org.jgroups.protocols.raft.LevelDBLog";

    @Property(description="Arguments to the log impl, e.g. k1=v1,k2=v2. These will be passed to init()")
    protected String                  log_args;

    @Property(description="The directory in which the log and snapshots are stored. Defaults to the temp dir")
    protected String                  log_dir=Util.checkForMac()?
      File.separator + "tmp" : System.getProperty("java.io.tmpdir", File.separator + "tmp");

    @Property(description="The name of the log. The logical name of the channel (if defined) is used by default. " +
      "Note that logs for different processes on the same host need to be different")
    protected String                  log_name;

    @Property(description="The name of the snapshot. By default, <log_name>.snapshot will be used")
    protected String                  snapshot_name;

    @Property(description="Interval (ms) at which AppendEntries messages are resent to members which haven't received them yet",
      type=AttributeType.TIME)
    protected long                    resend_interval=1000;

    @Property(description="Send commit message to followers immediately after leader commits (majority has consensus). Caution : it may generate more traffic than expected")
    protected boolean                 send_commits_immediately = false;

    @Property(description="Max number of bytes a log can have until a snapshot is created",type=AttributeType.BYTES)
    protected int                     max_log_size=1_000_000;

    /** task firing every resend_interval ms to send AppendEntries msgs to mbrs which are missing them */
    protected Future<?>               resend_task;

    protected StateMachine            state_machine;

    protected boolean                 state_machine_loaded;

    protected Log                     log_impl;

    protected RequestTable<String>    request_table;
    protected CommitTable             commit_table;

    protected final List<RoleChange>  role_change_listeners=new ArrayList<>();

    // Set to true during an addServer()/removeServer() op until the change has been committed
    protected final AtomicBoolean     members_being_changed = new AtomicBoolean(false);

    /** The current role (follower, candidate or leader). Every node starts out as a follower */
    protected volatile RaftImpl       impl=new Follower(this);
    protected volatile View           view;
    protected TimeScheduler           timer;

    /** The current leader (can be null if there is currently no leader) */
    protected volatile Address        leader;

    /** The current term. Incremented when this node becomes a candidate, or set when a higher term is seen */
    @ManagedAttribute(description="The current term")
    protected int                     current_term;

    @ManagedAttribute(description="Index of the highest log entry appended to the log",type=AttributeType.SCALAR)
    protected int                     last_appended;

    @ManagedAttribute(description="Index of the highest committed log entry",type=AttributeType.SCALAR)
    protected int                     commit_index;

    @ManagedAttribute(description="The number of snapshots performed")
    protected int                     num_snapshots;

    @ManagedAttribute(description="The number of times AppendEntriesRequests were resent")
    protected int                     num_resends=0;

    protected boolean                 snapshotting;

    protected int                     log_size_bytes; // keeps counts of the bytes added to the log

    @Property(description="Max size in items the processing queue can have",type=AttributeType.SCALAR)
    protected int                     processing_queue_max_size=9182;

    protected BlockingQueue<Request>  processing_queue;

    protected final List<Request>     remove_queue=new ArrayList<>();

    protected Runner                  runner;


    public String       raftId()                      {return raft_id;}
    public RAFT         raftId(String id)             {if(id != null) this.raft_id=id; return this;}
    public int          majority()                    {synchronized(members) {return majority;}}
    public String       logClass()                    {return log_class;}
    public RAFT         logClass(String clazz)        {log_class=clazz; return this;}
    public String       logArgs()                     {return log_args;}
    public RAFT         logArgs(String args)          {log_args=args; return this;}
    public String       logName()                     {return log_name;}
    public RAFT         logName(String name)          {log_name=name; return this;}
    public String       snapshotName()                {return snapshot_name;}
    public RAFT         snapshotName(String name)     {snapshot_name=name; return this;}
    public long         resendInterval()              {return resend_interval;}
    public RAFT         resendInterval(long val)      {resend_interval=val; return this;}
    public boolean      sendCommitsImmediately()      {return send_commits_immediately;}
    public RAFT         sendCommitsImmediately(boolean val)      {send_commits_immediately=val; return this;}
    public int          maxLogSize()                  {return max_log_size;}
    public RAFT         maxLogSize(int val)           {max_log_size=val; return this;}

    @ManagedAttribute(description="Current leader")
    public String       getLeader()                   {return leader != null? leader.toString() : "none";}
    public Address      leader()                      {return leader;}
    public RAFT         leader(Address new_leader)    {this.leader=new_leader; return this;}
    public boolean      isLeader()                    {return Objects.equals(leader, local_addr);}
    public org.jgroups.logging.Log getLog()           {return this.log;}
    public RAFT         stateMachine(StateMachine sm) {this.state_machine=sm; return this;}
    public StateMachine stateMachine()                {return state_machine;}
    public int          currentTerm()                 {return current_term;}
    public int          lastAppended()                {return last_appended;}
    public int          commitIndex()                 {return commit_index;}
    public Log          log()                         {return log_impl;}
    public RAFT         log(Log new_log)              {this.log_impl=new_log; return this;}
    public RAFT         addRoleListener(RoleChange c) {this.role_change_listeners.add(c); return this;}
    public RAFT         remRoleListener(RoleChange c) {this.role_change_listeners.remove(c); return this;}
    @ManagedAttribute(description="Is the resend task running")
    public boolean      resendTaskRunning() {return resend_task != null && !resend_task.isDone();}

    public void resetStats() {
        super.resetStats();
        num_snapshots=num_resends=0;
    }

    @Property(description="List of members (logical names); majority is computed from it")
    public void setMembers(String list) {
        members(Util.parseCommaDelimitedStrings(list));
    }

    public RAFT members(Collection<String> list) {
        synchronized(members) {
            this.members.clear();
            this.members.addAll(new HashSet<>(list));
            computeMajority();
        }
        return this;
    }

    @Property
    public List<String> members() {
        synchronized (members) {
            return new ArrayList<>(members);
        }
    }


    /**
     * Sets current_term if new_term is bigger
     * @param new_term The new term
     * @return -1 if new_term is smaller, 0 if equal and 1 if new_term is bigger
     */
    public synchronized int currentTerm(final int new_term)  {
        if(new_term < current_term)
            return -1;
        if(new_term > current_term) {
            log.trace("%s: changed term from %d -> %d", local_addr, current_term, new_term);
            current_term=new_term;
            log_impl.currentTerm(new_term);
            return 1;
        }
        return 0;
    }

    @ManagedAttribute(description="The current role")
    public String role() {
        RaftImpl tmp=impl;
        return tmp.getClass().getSimpleName();
    }

    @ManagedOperation(description="Dumps the commit table")
    public String dumpCommitTable() {
        return commit_table != null? commit_table.toString() : "n/a";
    }

    @ManagedAttribute(description="Number of log entries in the log")
    public int logSize() {return log_impl.size();}


    /** This is a managed operation because it should invoked sparingly (costly) */
    @ManagedOperation(description="Number of bytes in the log")
    public int logSizeInBytes() {
        final AtomicInteger count=new AtomicInteger(0);
        log_impl.forEach((entry,index) -> count.addAndGet(entry.length()));
        return count.intValue();
    }

    @ManagedOperation(description="Dumps the last N log entries")
    public String dumpLog(int last_n) {
        final StringBuilder sb=new StringBuilder();
        int to=last_appended, from=Math.max(1, to-last_n);
        log_impl.forEach((entry,index) ->
                           sb.append("index=").append(index).append(", term=").append(entry.term()).append(" (")
                             .append(entry.command().length).append(" bytes)\n"),
                         from, to);
        return sb.toString();
    }

    @ManagedOperation(description="Dumps all log entries")
    public String dumpLog() {
        return dumpLog(last_appended - 1);
    }

    public void logEntries(ObjIntConsumer<LogEntry> func) {
        log_impl.forEach(func);
    }

    public synchronized int createNewTerm() {
        return ++current_term;
    }

    protected synchronized void createRequestTable() {
        request_table=new RequestTable<>();
        // Populate with non-committed entries (from log) (https://github.com/belaban/jgroups-raft/issues/31)
        for(int i=this.commit_index+1; i <= this.last_appended; i++)
            request_table.create(i, raft_id, null, majority());
    }

    protected void createCommitTable() {
        List<Address> jg_mbrs=view != null? view.getMembers() : new ArrayList<>();
        List<Address> mbrs=new ArrayList<>(jg_mbrs);
        mbrs.remove(local_addr);
        commit_table=new CommitTable(mbrs, last_appended +1);
    }

    public synchronized boolean updateTermAndLeader(int term, Address new_leader) {
        if(leader == null || (new_leader != null && !leader.equals(new_leader)))
            leader=new_leader;
        if(term > current_term) {
            current_term=term;
            return true;
        }
        return false;
    }

    @ManagedOperation(description="Adds a new server to members. Prevents duplicates")
    public CompletableFuture<byte[]> addServer(String name) throws Exception {
        return changeMembers(name, InternalCommand.Type.addServer);
    }

    @ManagedOperation(description="Removes a new server from members")
    public CompletableFuture<byte[]> removeServer(String name) throws Exception {
        return changeMembers(name, InternalCommand.Type.removeServer);
    }

    protected CompletableFuture<byte[]> changeMembers(String name, InternalCommand.Type type) throws Exception {
        if(!dynamic_view_changes)
            throw new Exception("dynamic view changes are not allowed; set dynamic_view_changes to true to enable it");
        if(leader == null || (local_addr != null && !leader.equals(local_addr)))
            throw new IllegalStateException("I'm not the leader (local_addr=" + local_addr + ", leader=" + leader + ")");

        if(members_being_changed.compareAndSet(false, true)) {
            InternalCommand cmd=new InternalCommand(type, name);
            byte[] buf=Util.streamableToByteBuffer(cmd);
            return setAsync(buf, 0, buf.length, cmd);
        } else {
            throw new IllegalStateException(type + "(" + name + ") cannot be invoked as previous operation has not yet been committed");
        }
    }

    void _addServer(String name) {
        if(name == null) return;
        synchronized(members) {
            if(!members.contains(name)) {
                members.add(name);
                computeMajority();
            }
        }
    }

    void _removeServer(String name) {
        if(name == null) return;
        synchronized(members) {
            if(members.remove(name))
                computeMajority();
        }
    }


    /**
     * Creates a new snapshot and truncates the log. See https://github.com/belaban/jgroups-raft/issues/7 for details
     */
    @ManagedOperation(description="Creates a new snapshot and truncates the log")
    public synchronized void snapshot() throws Exception {
        // todo: make sure all requests are blocked while dumping the snapshot
        if(snapshotting) {
            log.error("%s: cannot create snapshot; snapshot is being created by another thread");
            return;
        }
        try {
            snapshotting=true;
            doSnapshot();
            num_snapshots++;
        }
        finally {
            snapshotting=false;
        }
    }



    /**
     * Loads the log entries from [first .. commit_index] into the state machine
     */
    @ManagedOperation(description="Reads snapshot (if present) and log entries up to " +
      "commit_index and applies them to the state machine")
    public synchronized void initStateMachineFromLog(boolean force) throws Exception {
        if(state_machine != null) {
            if(!state_machine_loaded || force) {
                int snapshot_offset=0;
                try(InputStream input=new FileInputStream(snapshot_name)) {
                    state_machine.readContentFrom(new DataInputStream(input));
                    snapshot_offset=1;
                    log.debug("%s: initialized state machine from snapshot %s", local_addr, snapshot_name);
                }
                catch(FileNotFoundException fne) {
                    // log.debug("snapshot %s not found, initializing state machine from persistent log", snapshot_name);
                }

                int from=Math.max(1, log_impl.firstAppended()+snapshot_offset), to=commit_index, count=0;
                for(int i=from; i <= to; i++) {
                    LogEntry log_entry=log_impl.get(i);
                    if(log_entry == null) {
                        log.error("%s: log entry for index %d not found in log", local_addr, i);
                        break;
                    }
                    if(log_entry.command != null) {
                        if(log_entry.internal)
                            executeInternalCommand(null, log_entry.command, log_entry.offset, log_entry.length);
                        else {
                            state_machine.apply(log_entry.command, log_entry.offset, log_entry.length);
                            count++;
                        }
                    }
                }
                state_machine_loaded=true;
                if(count > 0)
                    log.debug("%s: applied %d entries from the log (%d - %d) to the state machine", local_addr, count, from, to);
            }
        }
    }

    @Override public void init() throws Exception {
        super.init();
        timer=getTransport().getTimer();

        // we can only add/remove 1 member at a time (section 4.1 of [1])
        synchronized(members) {
            Set<String> tmp=new HashSet<>(members);
            if(tmp.size() != members.size()) {
                log.error("members (%s) contains duplicates; removing them and setting members to %s", members, tmp);
                members.clear();
                members.addAll(tmp);
            }
            computeMajority();
        }

        // Set an AddressGenerator in channel which generates ExtendedUUIDs and adds the raft_id to the hashmap
        final JChannel ch=stack.getChannel();
        ch.addAddressGenerator(() -> {
            ExtendedUUID.setPrintFunction(print_function);
            return ExtendedUUID.randomUUID(ch.getName()).put(raft_id_key, Util.stringToBytes(raft_id));
        });

        processing_queue=new ArrayBlockingQueue<>(processing_queue_max_size);
        runner=new Runner(new DefaultThreadFactory("runner", true, true),
                          "runner", this::processQueue, null);
    }

    @Override public void start() throws Exception {
        super.start();

        if(raft_id == null)
            raft_id=InetAddress.getLocalHost().getHostName();

        synchronized (members) {
            if (!members.contains(raft_id))
                throw new IllegalStateException(String.format("raft-id %s is not listed in members %s", raft_id, this.members));
        }

        if(log_impl == null) {
            if(log_class == null)
                throw new IllegalStateException("log_class has to be defined");
            Class<?> clazz=Util.loadClass(log_class, getClass());
            log_impl=(Log)clazz.getDeclaredConstructor().newInstance();
            Map<String,String> args;
            if(log_args != null && !log_args.isEmpty())
                args=parseCommaDelimitedProps(log_args);
            else
                args=new HashMap<>();

            if(log_name == null)
                log_name=raft_id;
            snapshot_name=log_name;
            log_name=createLogName(log_name, "log");
            snapshot_name=createLogName(snapshot_name, "snapshot");
            log_impl.init(log_name, args);
        }

        if(!(local_addr instanceof ExtendedUUID))
            throw new IllegalStateException("local address must be an ExtendedUUID but is a " + local_addr.getClass().getSimpleName());

        last_appended=log_impl.lastAppended();
        commit_index=log_impl.commitIndex();
        current_term=log_impl.currentTerm();
        log.trace("set last_appended=%d, commit_index=%d, current_term=%d", last_appended, commit_index, current_term);
        if(snapshot_name != null)
            initStateMachineFromLog(false);
        log_size_bytes=logSizeInBytes();

        runner.start();
    }



    @Override public void stop() {
        super.stop();
        runner.stop();
        impl.destroy();
    }


    public Object down(Event evt) {
        switch(evt.getType()) {
            case Event.VIEW_CHANGE:
                handleView(evt.getArg());
                break;
        }
        return down_prot.down(evt);
    }


    public Object up(Event evt) {
        if(evt.getType() == Event.VIEW_CHANGE)
            handleView(evt.getArg());
        return up_prot.up(evt);
    }

    public Object up(Message msg) {
        RaftHeader hdr=msg.getHeader(id);
        if(hdr != null) {
            add(new UpRequest(msg, hdr));
            // handleEvent(msg, hdr);
            return null;
        }
        return up_prot.up(msg);
    }

    public void up(MessageBatch batch) {
        for(Iterator<Message> it = batch.iterator(); it.hasNext();) {
            Message msg=it.next();
            RaftHeader hdr=msg.getHeader(id);
            if(hdr != null) {
                it.remove();
                add(new UpRequest(msg, hdr));
                // handleEvent(msg, hdr);
            }
        }
        if(!batch.isEmpty())
            up_prot.up(batch);
    }

    /**
     * The blocking equivalent of {@link #setAsync(byte[],int,int)}. Used to apply a change
     * across all cluster nodes via consensus.
     * @param buf The serialized command to be applied (interpreted by the caller)
     * @param offset The offset into the buffer
     * @param length The length of the buffer
     * @return The serialized result (to be interpreted by the caller)
     * @throws Exception ExecutionException or InterruptedException
     */
    public byte[] set(byte[] buf, int offset, int length) throws Exception {
        CompletableFuture<byte[]> future=setAsync(buf, offset, length, null);
        return future.get();
    }

    /**
     * Time bounded blocking get(). Returns when the result is available, or a timeout (or exception) has occurred
     * @param buf The buffer
     * @param offset The offset into the buffer
     * @param length The length of the buffer
     * @param timeout The timeout
     * @param unit The unit of the timeout
     * @return The serialized result
     * @throws Exception ExecutionException when the execution failed, InterruptedException, or TimeoutException when
     * the timeout elapsed without getting an exception.
     */
    public byte[] set(byte[] buf, int offset, int length, long timeout, TimeUnit unit) throws Exception {
        CompletableFuture<byte[]> future=setAsync(buf, offset, length, null);
        return future.get(timeout, unit);
    }

    /**
     * Called by a building block to apply a change to all state machines in a cluster. This starts the consensus
     * protocol to get a majority to commit this change.<p/>
     * This call is non-blocking and returns a future as soon as the AppendEntries message has been sent.<p/>
     * Only applicable on the leader
     * @param buf The command
     * @param offset The offset into the buffer
     * @param length The length of the buffer
     * @return A CompletableFuture. Can be used to wait for the result (sync). A blocking caller could call
     *         set(), then call future.get() to block for the result.
     */
    public CompletableFuture<byte[]> setAsync(byte[] buf, int offset, int length) {
        return setAsync(buf, offset, length, null);
    }

    protected CompletableFuture<byte[]> setAsync(byte[] buf, int offset, int length, InternalCommand cmd) {
        if(leader == null || (local_addr != null && !leader.equals(local_addr)))
            throw new IllegalStateException("I'm not the leader (local_addr=" + local_addr + ", leader=" + leader + ")");
        if(buf == null)
            throw new IllegalArgumentException("buffer must not be null");

        CompletableFuture<byte[]> retval=new CompletableFuture<>();
        RequestTable<String> reqtab=request_table;
        if(reqtab == null) {
            retval.completeExceptionally(new IllegalStateException("request table was null on " + impl.getClass().getSimpleName()));
            return retval;
        }

        DownRequest r=new DownRequest(retval, buf, offset, length, cmd);
        add(r);

        /*// 1. Append to the log
        synchronized(this) {
            prev_index=last_appended;
            curr_index=++last_appended;
            curr_term=current_term;
            commit_idx=commit_index;
            LogEntry entry=log_impl.get(prev_index);
            prev_term=entry != null? entry.term : 0;

            log_impl.append(curr_index, true, new LogEntry(curr_term, buf, offset, length, cmd != null));

            if(cmd != null)
                executeInternalCommand(cmd, null, 0, 0);

            // 2. Add the request to the client table, so we can return results to clients when done
            reqtab.create(curr_index, raft_id, retval, majority());

            // 3. Multicast an AppendEntries message (exclude self)
            Message msg=new BytesMessage(null, buf, offset, length)
              .putHeader(id, new AppendEntriesRequest(curr_term, this.local_addr, prev_index, prev_term, curr_term, commit_idx, cmd != null))
              .setFlag(Message.TransientFlag.DONT_LOOPBACK); // don't receive my own request

            // the message needs to be sent inside the synchronized block (and hit NAKACK2 in the correct order):
            // updates (prev-index:current-index) 4:5 and 5:6 would fail if 5:6 was applied first, as 5 would not exist
            // in the 5:6 AppendEntriesRequest
            down_prot.down(msg); // *** don't move outside the synchronized block! ****

            // needs to be synchronized, too, as commits might diverge from the snapshot below...
            snapshotIfNeeded(length);
            if(reqtab.isCommitted(curr_index))
                handleCommit(curr_index);
        }
*/
        // 4. Return CompletableFuture
        return retval;
    }

    protected CompletableFuture<byte[]> _setAsync(CompletableFuture<byte[]> retval, byte[] buf, int offset, int length,
                                                  InternalCommand cmd) {
        if(leader == null || (local_addr != null && !leader.equals(local_addr)))
            throw new IllegalStateException("I'm not the leader (local_addr=" + local_addr + ", leader=" + leader + ")");

        int prev_index=0, curr_index=0, prev_term=0, curr_term=0, commit_idx=0;

        RequestTable<String> reqtab=request_table;

        // 1. Append to the log
        synchronized(this) {
            prev_index=last_appended;
            curr_index=++last_appended;
            curr_term=current_term;
            commit_idx=commit_index;
            LogEntry entry=log_impl.get(prev_index);
            prev_term=entry != null? entry.term : 0;

            log_impl.append(curr_index, true, new LogEntry(curr_term, buf, offset, length, cmd != null));

            if(cmd != null)
                executeInternalCommand(cmd, null, 0, 0);

            // 2. Add the request to the client table, so we can return results to clients when done
            reqtab.create(curr_index, raft_id, retval, majority());

            // 3. Multicast an AppendEntries message (exclude self)
            Message msg=new BytesMessage(null, buf, offset, length)
              .putHeader(id, new AppendEntriesRequest(curr_term, this.local_addr, prev_index, prev_term, curr_term, commit_idx, cmd != null))
              .setFlag(Message.TransientFlag.DONT_LOOPBACK); // don't receive my own request

            // the message needs to be sent inside the synchronized block (and hit NAKACK2 in the correct order):
            // updates (prev-index:current-index) 4:5 and 5:6 would fail if 5:6 was applied first, as 5 would not exist
            // in the 5:6 AppendEntriesRequest
            down_prot.down(msg); // *** don't move outside the synchronized block! ****

            // needs to be synchronized, too, as commits might diverge from the snapshot below...
            snapshotIfNeeded(length);
            if(reqtab.isCommitted(curr_index))
                handleCommit(curr_index);
        }

        // 4. Return CompletableFuture
        return retval;
    }


    protected void processQueue() {
        Request first_req;
        try {
            first_req=processing_queue.take();
            if(first_req == null)
                return;

            for(;;) {
                remove_queue.clear();
                if(first_req != null) {
                    remove_queue.add(first_req);
                    first_req=null;
                }

                processing_queue.drainTo(remove_queue);
                if(remove_queue.isEmpty())
                    return;
                else
                    process(remove_queue);
            }
        }
        catch(InterruptedException e) {

        }
    }

    protected void add(Request r) {
        try {
            processing_queue.put(r);
        }
        catch(InterruptedException ex) {
            log.error("%s: failed adding %s to processing queue: %s", local_addr, r, ex);
        }
    }

    @ManagedAttribute
    protected final LongAdder num_up_requests=new LongAdder();

    @ManagedAttribute
    protected final LongAdder num_down_requests=new LongAdder();

    protected void process (List<Request> q) {
        for(Request r: q) {
            try {
                if(r instanceof UpRequest) {
                    UpRequest up=(UpRequest)r;
                    handleEvent(up.msg, up.hdr);
                    num_up_requests.increment();
                }
                else if(r instanceof DownRequest) {
                    DownRequest dr=(DownRequest)r;
                    _setAsync(dr.f, dr.buf, dr.offset, dr.length, dr.cmd);
                    num_down_requests.increment();
                }
            }
            catch(Exception ex) {
                log.error("%s: failed handling request %s: %s", local_addr, r, ex);
            }
        }
    }

    protected void handleEvent(Message msg, RaftHeader hdr) {
        // if hdr.term < current_term -> drop message
        // if hdr.term > current_term -> set current_term and become Follower, accept message
        // if hdr.term == current_term -> accept message
        if(currentTerm(hdr.term) < 0)
            return;

        if(hdr instanceof AppendEntriesRequest) {
            AppendEntriesRequest req=(AppendEntriesRequest)hdr;
            AppendResult result=impl.handleAppendEntriesRequest(msg.getArray(), msg.getOffset(), msg.getLength(), msg.src(),
                                                                req.prev_log_index, req.prev_log_term, req.entry_term,
                                                                req.leader_commit, req.internal);
            if(result != null) {
                result.commitIndex(commit_index);
                Message rsp=new EmptyMessage(leader).putHeader(id, new AppendEntriesResponse(current_term, result));
                down_prot.down(rsp);
            }
        }
        else if(hdr instanceof AppendEntriesResponse) {
            AppendEntriesResponse rsp=(AppendEntriesResponse)hdr;
            impl.handleAppendEntriesResponse(msg.src(),rsp.term(), rsp.result);
        }
        else if(hdr instanceof InstallSnapshotRequest) {
            InstallSnapshotRequest req=(InstallSnapshotRequest)hdr;
            impl.handleInstallSnapshotRequest(msg, req.term(), req.leader, req.last_included_index, req.last_included_term);
        }
        else
            log.warn("%s: invalid header %s",local_addr,hdr.getClass().getCanonicalName());
    }

    /**
     * Runs (on the leader) every resend_interval ms: checks if all members in the commit table have received
     * all messages and resends AppendEntries messages to members who haven't. <p/>
     * For each member a next-index and match-index is maintained: next-index is the index of the next message to send to
     * that member (initialized to last-applied) and match-index is the index of the highest message known to have
     * been received by the member.<p/>
     * Messages are resent to a given member as long as that member's match-index is smaller than its next-index. When
     * match_index == next_index, message resending for that member is stopped. When a new message is appended to the
     * leader's log, next-index for all members is incremented and resending starts again.
     */
    @Override public void run() {
        if(commit_table != null)
            commit_table.forEach(this::sendAppendEntriesMessage);
    }

    protected void sendAppendEntriesMessage(Address member, CommitTable.Entry entry) {
        int next_index=entry.nextIndex(), commit_idx=entry.commitIndex(), match_index=entry.matchIndex();
        if(next_index < log().firstAppended()) {
            if(entry.snapshotInProgress(true)) {
                try {
                    sendSnapshotTo(member); // will reset snapshot_in_progress // todo: run in separate thread
                }
                catch(Exception e) {
                    log.error("%s: failed sending snapshot to %s: next_index=%d, first_applied=%d",
                              local_addr, member, next_index, log().firstAppended());
                }
            }
            return;
        }

        if(this.last_appended >= next_index) {
            int to=entry.sendSingleMessage()? next_index : last_appended;
            for(int i=Math.max(next_index,1); i <= to; i++) {  // i=match_index+1 ?
                if(log.isTraceEnabled())
                    log.trace("%s: resending %d to %s\n", local_addr, i, member);
                resend(member, i);
            }
            return;
        }

        if(this.last_appended > match_index) {
            int index=this.last_appended;
            if(index > 0) {
                log.trace("%s: resending %d to %s\n", local_addr, index, member);
                resend(member, index);
            }
            return;
        }

        if(this.commit_index > commit_idx) { // send an empty AppendEntries message as commit message
            Message msg=new EmptyMessage(member).putHeader(id, new AppendEntriesRequest(current_term, this.local_addr, 0, 0, 0, this.commit_index, false));
            down_prot.down(msg);
            return;
        }

        if(this.commit_index < this.last_appended) { // fixes https://github.com/belaban/jgroups-raft/issues/30
            for(int i=this.commit_index+1; i <= this.last_appended; i++)
                resend(member, i);
        }
    }


    protected void resend(Address target, int index) {
        LogEntry entry=log_impl.get(index);
        if(entry == null) {
            log.error("%s: resending of %d failed; entry not found", local_addr, index);
            return;
        }
        LogEntry prev=log_impl.get(index-1);
        int prev_term=prev != null? prev.term : 0;
        Message msg=new BytesMessage(target).setArray(entry.command, entry.offset, entry.length)
          .putHeader(id, new AppendEntriesRequest(current_term, this.local_addr, index - 1, prev_term, entry.term, commit_index, entry.internal));
        down_prot.down(msg);
        num_resends++;
    }

    protected void doSnapshot() throws Exception {
        if(state_machine == null)
            throw new IllegalStateException("state machine is null");
        try (OutputStream output=new FileOutputStream(snapshot_name)) {
            state_machine.writeContentTo(new DataOutputStream(output));
        }
        log_impl.truncate(commitIndex());
    }

    protected boolean snapshotExists() {
        File file=new File(snapshot_name);
        return file.exists();
    }

    public RAFT deleteSnapshot() {
        File file=new File(snapshot_name);
        file.delete();
        return this;
    }

    public RAFT deleteLog() throws Exception {
        if(log_impl != null) {
            log_impl.delete();
            log_impl=null;
        }
        return this;
    }


    protected synchronized void sendSnapshotTo(Address dest) throws Exception {
        try {
            if(snapshotting)
                return;
            snapshotting=true;

            LogEntry last_committed_entry=log_impl.get(commitIndex());
            int last_index=commit_index, last_term=last_committed_entry.term;
            doSnapshot();

            byte[] data=Files.readAllBytes(Paths.get(snapshot_name));
            log.debug("%s: sending snapshot (%s) to %s", local_addr, Util.printBytes(data.length), dest);
            Message msg=new BytesMessage(dest, data)
              .putHeader(id, new InstallSnapshotRequest(currentTerm(), leader(), last_index, last_term));
            down_prot.down(msg);

        }
        finally {
            snapshotting=false;
            if(commit_table != null)
                commit_table.snapshotInProgress(dest, false);
        }
    }

    /**
     * Received a majority of votes for the entry at index. Note that indices may be received out of order, e.g. if
     * we have modifications at indixes 4, 5 and 6, entry[5] might get a majority of votes (=committed)
     * before entry[3] and entry[6].<p/>
     * The following things are done:
     * <ul>
     *     <li>See if commit_index can be moved to index (incr commit_index until a non-committed entry is encountered)</li>
     *     <li>For each committed entry, apply the modification in entry[index] to the state machine</li>
     *     <li>For each committed entry, notify the client and set the result (CompletableFuture)</li>
     * </ul>
     *
     * @param index The index of the committed entry.
     */
    protected synchronized void handleCommit(int index) {
        try {
            for(int i=commit_index + 1; i <= Math.min(index, last_appended); i++) {
                if(request_table.isCommitted(i)) {
                    applyCommit(i);
                    commit_index=Math.max(commit_index, i);
                }
            }
        }
        catch(Throwable t) {
            log.error("failed applying commit %d: %s", index, t);
        }
    }

    /**
     * Tries to advance commit_index up to leader_commit, applying all uncommitted log entries to the state machine
     * @param leader_commit The commit index of the leader
     */
    protected synchronized RAFT commitLogTo(int leader_commit) {
        int old_commit=commit_index, to=Math.min(last_appended, leader_commit);
        try {
            for(int i=commit_index+1; i <= to; i++) {
                applyCommit(i);
                commit_index=Math.max(commit_index, i);
            }
        }
        catch(Throwable t) {
            log.error("%s: failed moving commit_index from (exclusive) %d to (inclusive) %d " +
                        "(last_appended=%d, leader's commit_index=%d, failed at commit_index %d)): %s",
                      local_addr, old_commit, to, last_appended, leader_commit, commit_index+1,  t);
        }
        return this;
    }

    protected synchronized RAFT append(int term, int index, byte[] data, int offset, int length, boolean internal) {
        if(index > last_appended) {
            LogEntry entry=new LogEntry(term, data, offset, length, internal);
            log_impl.append(index, true, entry);
            last_appended=log_impl.lastAppended();
        }
        snapshotIfNeeded(length);
        return this;
    }

    protected void deleteAllLogEntriesStartingFrom(int index) {
        log_impl.deleteAllEntriesStartingFrom(index);
        last_appended=log_impl.lastAppended();
        commit_index=log_impl.commitIndex();
    }


    protected void snapshotIfNeeded(int bytes_added) {
        log_size_bytes+=bytes_added;
        if(log_size_bytes >= max_log_size) {
            try {
                this.log.debug("%s: current log size is %d, exceeding max_log_size of %d: creating snapshot",
                               local_addr, log_size_bytes, max_log_size);
                snapshot();
                log_size_bytes=logSizeInBytes();
            }
            catch(Exception ex) {
                log.error("%s: failed snapshotting log: %s", local_addr, ex);
            }
        }
    }


    /** Applies the commit at index */
    protected void applyCommit(int index) throws Exception {
        // Apply the modifications to the state machine
        LogEntry log_entry=log_impl.get(index);
        if(log_entry == null)
            throw new IllegalStateException(local_addr + ": log entry for index " + index + " not found in log");
        if(state_machine == null)
            throw new IllegalStateException(local_addr + ": state machine is null");
        byte[] rsp=null;
        if(log_entry.internal) {
            InternalCommand cmd;
            try {
                cmd=Util.streamableFromByteBuffer(InternalCommand.class, log_entry.command,
                                                  log_entry.offset, log_entry.length);
                if(cmd.type() == InternalCommand.Type.addServer || cmd.type() == InternalCommand.Type.removeServer)
                    members_being_changed.set(false); // new addServer()/removeServer() operations can now be started
            }
            catch(Throwable t) {
                log.error("%s: failed unmarshalling internal command: %s", local_addr, t);
            }
        }
        else
            rsp=state_machine.apply(log_entry.command, log_entry.offset, log_entry.length);

        log_impl.commitIndex(index);

        // Notify the client's CompletableFuture and then remove the entry in the client request table
        if(request_table != null)
            request_table.notifyAndRemove(index, rsp);
    }

    protected void handleView(View view) {
        boolean check_view=this.view != null && this.view.size() < view.size();
        this.view=view;
        if(commit_table != null) {
            List<Address> mbrs=new ArrayList<>(view.getMembers());
            mbrs.remove(local_addr);
            commit_table.adjust(mbrs, last_appended + 1);
        }

        // if we're the leader, check if the view contains no duplicate raft-ids
        if(check_view && duplicatesInView(view))
            log.error("view contains duplicate raft-ids: %s", view);
    }

    protected void changeRole(Role new_role) {
        RaftImpl new_impl=new_role == Role.Follower? new Follower(this) : new_role == Role.Candidate? new Candidate(this) : new Leader(this);
        RaftImpl old_impl=impl;
        if(old_impl == null || !old_impl.getClass().equals(new_impl.getClass())) {
            if(old_impl != null)
                old_impl.destroy();
            new_impl.init();
            synchronized(this) {
                impl=new_impl;
            }
            log.trace("%s: changed role from %s -> %s", local_addr, old_impl == null? "null" :
              old_impl.getClass().getSimpleName(), new_impl.getClass().getSimpleName());
            notifyRoleChangeListeners(new_role);
        }
    }




    /** If cmd is not null, execute it. Else parse buf into InternalCommand then call cmd.execute() */
    protected void executeInternalCommand(InternalCommand cmd, byte[] buf, int offset, int length) {
        if(cmd == null) {
            try {
                cmd=Util.streamableFromByteBuffer(InternalCommand.class, buf, offset, length);
            }
            catch(Exception ex) {
                log.error("%s: failed unmarshalling internal command: %s", local_addr, ex);
                return;
            }
        }

        try {
            cmd.execute(this);
        }
        catch(Exception ex) {
            log.error("%s: failed executing internal command %s: %s", local_addr, cmd, ex);
        }
    }

    protected synchronized void startResendTask() {
        if(resend_task == null || resend_task.isDone())
            resend_task=timer.scheduleWithFixedDelay(this, resend_interval, resend_interval, TimeUnit.MILLISECONDS);
    }

    protected synchronized void stopResendTask() {
        if(resend_task != null) {
            resend_task.cancel(false);
            resend_task=null;
        }
    }

    public static <T> T findProtocol(Class<T> clazz, final Protocol start, boolean down) {
        Protocol prot=start;
        while(prot != null && clazz != null) {
            if(clazz.isAssignableFrom(prot.getClass()))
                return (T)prot;
            prot=down? prot.getDownProtocol() : prot.getUpProtocol();
        }
        return null;
    }

    // Replace with Util equivalent when switching to JGroups 3.6.2 or when merging this code into JGroups
    public static <T extends Streamable> void write(T[] array, DataOutput out) throws Exception {
        Bits.writeIntCompressed(array != null? array.length : 0, out);
        if(array == null)
            return;
        for(T el: array)
            el.writeTo(out);
    }

    // Replace with Util equivalent when switching to JGroups 3.6.2 or when merging this code into JGroups
    public static <T extends Streamable> T[] read(Class<T> clazz, DataInput in) throws Exception {
        int size=Bits.readIntCompressed(in);
        if(size == 0)
            return null;
        T[] retval=(T[])Array.newInstance(clazz, size);

        for(int i=0; i < retval.length; i++) {
            retval[i]=clazz.newInstance();
            retval[i].readFrom(in);
        }
        return retval;
    }


    protected String createLogName(String name, String suffix) {
        if(!suffix.startsWith("."))
            suffix="." + suffix;
        boolean needs_suffix=!name.endsWith(suffix);
        String retval=name;
        if(!new File(name).isAbsolute()) {
            retval=log_dir + File.separator + name;
        }
        return needs_suffix? retval + suffix : retval;
    }

    protected void notifyRoleChangeListeners(Role role) {
        for(RoleChange ch: role_change_listeners) {
            try {
                ch.roleChanged(role);
            }
            catch(Throwable ignored) {}
        }
    }

    /**
     * Checks if a given view contains duplicate raft-ids. Uses key raft-id in ExtendedUUID to compare
     * @param view
     * @return
     */
    protected boolean duplicatesInView(View view) {
        Set<String> mbrs=new HashSet<>();
        for(Address addr : view) {
            if(!(addr instanceof ExtendedUUID))
                log.warn("address %s is not an ExtendedUUID but a %s", addr, addr.getClass().getSimpleName());
            else {
                ExtendedUUID uuid=(ExtendedUUID)addr;
                byte[] val=uuid.get(raft_id_key);
                String m=val != null? Util.bytesToString(val) : null;
                if(m == null)
                    log.error("address %s doesn't have a raft-id", addr);
                else if(!mbrs.add(m))
                    return true;
            }
        }
        return false;
    }

    protected static Map<String,String> parseCommaDelimitedProps(String s) {
        if (s == null)
            return null;
        Map<String,String> props=new HashMap<>();
        Pattern p=Pattern.compile("\\s*([^=\\s]+)\\s*=\\s*([^=\\s,]+)\\s*,?"); //Pattern.compile("\\s*([^=\\s]+)\\s*=\\s([^=\\s]+)\\s*,?");
        Matcher matcher=p.matcher(s);
        while(matcher.find()) {
            props.put(matcher.group(1), matcher.group(2));
        }
        return props;
    }

    /** number of requests being processed */
    public int requestTableSize() {
        return request_table.size();
    }

    public interface RoleChange {
        void roleChanged(Role role);
    }

    @GuardedBy("members")
    private void computeMajority() {
        majority = (members.size() / 2) + 1;
    }


    protected static class Request {

    }

    /** Received by up(Message) or up(MessageBatch) */
    protected static class UpRequest extends Request {
        private final Message    msg;
        private final RaftHeader hdr;

        public UpRequest(Message msg, RaftHeader hdr) {
            this.msg=msg;
            this.hdr=hdr;
        }

        public String toString() {
            return String.format("%s %s", UpRequest.class.getSimpleName(), hdr);
        }
    }

    /** Generated by {@link org.jgroups.protocols.raft.RAFT#setAsync(byte[], int, int)} */
    protected static class DownRequest extends Request {
        final CompletableFuture<byte[]> f;
        final byte[]                    buf;
        final int                       offset, length;
        final InternalCommand           cmd;

        public DownRequest(CompletableFuture<byte[]> f, byte[] buf, int offset, int length, InternalCommand cmd) {
            this.f=f;
            this.buf=buf;
            this.offset=offset;
            this.length=length;
            this.cmd=cmd;
        }

        public String toString() {
            return String.format("%s %d bytes", DownRequest.class.getSimpleName(), length);
        }
    }
}
