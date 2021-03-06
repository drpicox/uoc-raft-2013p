/*
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
*
* This file is part of the practical assignment of Distributed Systems course.
*
* This code is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This code is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this code.  If not, see <http://www.gnu.org/licenses/>.
*/

package recipesService.raft;


import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import recipesService.CookingRecipes;
import recipesService.communication.Host;
import recipesService.data.AddOperation;
import recipesService.data.Operation;
import recipesService.data.OperationType;
import recipesService.data.RemoveOperation;
import recipesService.raft.dataStructures.LogEntry;
import recipesService.raft.dataStructures.PersistentState;
import recipesService.raftRPC.AppendEntriesResponse;
import recipesService.raftRPC.RequestVoteResponse;
import recipesService.test.client.RequestResponse;

import communication.DSException;
import communication.rmi.RMIsd;

/**
 * 
 * Raft Consensus
 * (application skeletton from Joan Marques @ UOC)
 * 
 * @author David Rodenas
 * Oct 2013
 *
 */

public abstract class RaftConsensusDR extends CookingRecipes implements Raft{

	private static final boolean LOG_LEADER    = true;
	private static final boolean LOG_OPERATION = false;
	private static final boolean LOG_CLIENT    = false;
	
	// current server
	private Host localHost;
	
	//
	// STATE
	//
	
	// raft persistent state state on all servers
	protected PersistentState persistentState;  

	// raft volatile state on all servers
	private int commitIndex; // index of highest log entry known to be committed (initialized to 0, increases monotonically) 
	private int lastApplied; // index of highest log entry applied to state machine (initialized to 0, increases monotonically) 
	
	// other 
	private RaftState state = RaftState.FOLLOWER;
	
	// Leader election
	private long electionTimeout; // period of time that a follower receives no communication.
									// If a timeout occurs, it assumes there is no viable leader.
	
	//
	// LEADER
	//

	// Volatile state on leaders
	//private Index nextIndex; // for each server, index of the next log entry to send to that server (initialized to leader last log index + 1)
	//private Index matchIndex; // for each server, index of highest log known to be replicated on server (initialized to 0, increases monotonically)
	private final Map<Host,Integer> nextIndexes = new HashMap<>();  
	private final Map<Host,Integer> matchIndexes = new HashMap<>();  
	
	// Heartbeats on leaders
	private long leaderHeartbeatTimeout;
	
	//
	// CLUSTER
	//
	
	// partner servers
	private List<Host> otherServers; // list of partner servers (localHost not included in the list)

	
	//!--- ----------------------------------------------- Classes
	//!--- Executor queue
	private static final ExecutorService executorQueue = Executors.newCachedThreadPool();	
	//!--- Timer queues
	private final Timer timerQueue = new Timer(true); // is a daemon 
	//!--- Timer task for election timeout
	private TimerTask electionTimeoutTask = new TimerTask() {
		@Override
		public void run() {
			electionTimeout();
		}
	};
	//!--- Timer task for election timeout
	private TimerTask leaderHeartbeatTimeoutTask = new TimerTask() {
		@Override
		public void run() {
			leaderHeartbeatTimeout();
		}
	};
	//!--- Fictional timer task to commit entries
	private TimerTask commitTimeoutTask = new TimerTask() {
		@Override
		public void run() {
			commitTimeout();
		}
	};
	//!--- Syncrhonizer/Serializer object
	private final Object GUARD = new Object(); 
	
	
	
	
	// =======================
	// === IMPLEMENTATION
	// =======================
	
	public RaftConsensusDR(long electionTimeout){ // electiontimeout is a parameter in config.properties file
		// set electionTimeout
		this.electionTimeout = electionTimeout;
		
		//set leaderHeartbeatTimeout
		this.leaderHeartbeatTimeout = electionTimeout / 3; 
	}

	private String ID() {
		return ID(localHost);
	}
	private static String ID(Host host) {
		return ID(host.getId());
	}
	private static String ID(String id) {
		return id.split(":")[1].substring(2);
	}
	private static String ID(Operation op) {
		return op.getTimestamp().toString().substring(6);
	}
	private void log(String text) {
		final long term;
		final String state;
		final int lastIndex;
		final long lastTerm;
		synchronized (GUARD) {
			term = persistentState.getCurrentTerm();
			state = this.state.toString();
			lastIndex = persistentState.getLastLogIndex();
			lastTerm = persistentState.getLastLogTerm();
		}
		System.out.println("LOG:"+ID()+"("+term+")-"+state+":["+lastIndex+"]("+lastTerm+"):"+text);
	}
	private void log(Operation op, String text) {
		log(ID(op)+":"+op.getType()+":"+text);
	}
	
	// sets localhost and other servers participating in the cluster
	protected void setServers(
			Host localHost,
			List<Host> otherServers
			){

		this.localHost = localHost; 

		// initialize persistent state  on all servers
		persistentState = new PersistentState();
		
		// set servers list
		this.otherServers = otherServers;
		
		// start timers to talk with other servers
		timerQueue.schedule(electionTimeoutTask, electionTimeout, electionTimeout);		
		timerQueue.schedule(leaderHeartbeatTimeoutTask, leaderHeartbeatTimeout, leaderHeartbeatTimeout);
		timerQueue.schedule(commitTimeoutTask, leaderHeartbeatTimeout, leaderHeartbeatTimeout);
	}

	private final AtomicBoolean connected = new AtomicBoolean(false);
	
	// connect
	public void connect(){
		/*
		 *  ACTIONS TO DO EACH TIME THE SERVER CONNECTS (i.e. when it starts or after a failure or disconnection)
		 */
		seenLeader.set(true);
		connected.set(true); 
	}
	
	public void disconnect(){
		connected.set(false);
	}
	
	//
	// COMMON UTILITIES
	//
	
	/*
	 * Update term and related to this term.
	 */
	
	private void checkReceivedTerm(final long term) {
		synchronized (GUARD) {
			long myTerm = persistentState.getCurrentTerm();
			if (term > myTerm) {
				if (LOG_LEADER) if(state == RaftState.LEADER) {
					log("Stepdown:checkReceivedTerm:term:"+term);
				}
				
				// ops... I'm getting old
				persistentState.setCurrentTerm(term);
				persistentState.removeVotedFor();
				state = RaftState.FOLLOWER;
			}
		}
	}
	

	//
	// LEADER ELECTION
	//

	/*
	 *  Leader election
	 */
	private final AtomicBoolean seenLeader = new AtomicBoolean(false);

	private void electionTimeout() { 
		if (!connected.get()) { return; }
		// abort timeout if leader has been seen
		if (seenLeader.getAndSet(false)) { return; }
		
		// report action
		if (LOG_LEADER) log("Election timeout");
		
		// get values for the RequestVote RPC
		final long term;
		final String candidateId = localHost.getId();
		final int lastLogIndex;
		final long lastLogTerm;
		synchronized (GUARD) {
			// Leaders are free of election timeouts
			if (state == RaftState.LEADER) { return; } 
			
			// So here only FOLLOWER and CANDIDATE
			term = persistentState.getCurrentTerm() + 1;
			lastLogIndex = persistentState.getLastLogIndex();
			lastLogTerm = persistentState.getLastLogTerm();

			// And I'm now officially a CANDIDATE
			persistentState.setCurrentTerm(term);
			persistentState.setVotedFor(candidateId);
			state = RaftState.CANDIDATE;
		}
		
		// prepare vote counting
		final AtomicInteger voteCount = new AtomicInteger();
		final int minimumVoteCount = (otherServers.size() + 1) / 2;
		
		// place to record votes for the log
		final List<String> votesFrom;
		if (LOG_LEADER) { 
			votesFrom = new CopyOnWriteArrayList<>();
			votesFrom.add(ID()); 
		} else {
			votesFrom = null;
		}
		
		// request votes
		for (final Host otherHost : otherServers) {
			executorQueue.execute(new Runnable() {
				@Override
				public void run() {
					try {
						RequestVoteResponse response = RMIsd.getInstance().requestVote(otherHost, term, candidateId, lastLogIndex, lastLogTerm);
						// not now or not me
						if (response.getTerm() != term || !response.isVoteGranted()) {
							checkReceivedTerm(term);							
							return;
						}

						// record the vote for the log
						if (LOG_LEADER) {
							votesFrom.add(ID(otherHost.getId()));
						}
						
						// who wons?
						int votes = voteCount.incrementAndGet(); 
						if (votes == minimumVoteCount) {
							// I'm a winer??? (with less votes not yet, with more I'm already a leader)
							synchronized (GUARD) {
								if (persistentState.getCurrentTerm() != term || state != RaftState.CANDIDATE) {
									// Ops! Something changed while network RPC go and come
									return;
								}
								// I'm the leader
								state = RaftState.LEADER;
								
								// Reset nextIndex and matchIndex
								int nextIndex = persistentState.getLastLogIndex() + 1;
								for (Host otherHost : otherServers) {
									nextIndexes.put(otherHost, nextIndex);
									matchIndexes.put(otherHost, 0);
								}
								
								// action log
								if (LOG_LEADER) {
									log("Takeover:votesFrom:"+votesFrom);
								}
							}
						} // else greater values ignored to avoid become leader to often
					} catch (DSException e) { 						
					} catch (Exception e) {
						e.printStackTrace();
					}
					
				}
			});
		}
		
	}	

	@Override
	public RequestVoteResponse requestVote(final long term, final String candidateId,
			final int lastLogIndex, final long lastLogTerm) throws RemoteException {
		if (!connected.get()) { return new RequestVoteResponse(term, false); }
		if (term < 0) throw new IllegalArgumentException("illegal argument: term cannot be negative");
		if (candidateId == null) throw new IllegalArgumentException("illegal argument: candidateId cannot be null");
		if (lastLogIndex < 0) throw new IllegalArgumentException("illegal argument: lastLogIndex cannot be negative");
		
		checkReceivedTerm(term);
		
		// compute answer
		final long myTerm;
		final boolean granted;
		synchronized (GUARD) {
			// get current state
			myTerm = persistentState.getCurrentTerm();
			int myLastLogIndex = persistentState.getLastLogIndex();
			long myLastLogTerm = persistentState.getLastLogTerm();
			String votedFor = persistentState.getVotedFor();
			
			// check that current state is to make a positive vote
			if (true &&
					term == myTerm &&
					(votedFor == null || votedFor.equals(candidateId)) && 
					(myLastLogTerm <  lastLogTerm || (myLastLogTerm == lastLogTerm && myLastLogIndex <= lastLogIndex))					
					) {
				// give a positive vote champinyon!
				granted = true;
				persistentState.setVotedFor(candidateId);
				if (LOG_LEADER) {
					log("VoteFor:"+ID(candidateId));
				}
				
				// I have seen a future leader
				seenLeader.set(true);
			} else {
				// negative vote
				granted = false;
			}
		}
		
		return new RequestVoteResponse(myTerm, granted);
	}


	//
	// LOG REPLICATION
	//

	/*
	 *  Log replication.
	 *  Heartbeat also replicates the log, no special treatment required.
	 */

	private void leaderHeartbeatTimeout() {
		// if no connection now do nothing
		if (!connected.get()) { return; }
		
		// generate and send a appendEntries message for each otherServer 
		final String leaderId = localHost.getId();
		for (final Host otherServer: otherServers) {
			
			final long term;
			final int prevLogIndex;
			final long prevLogTerm;
			final List<LogEntry> entries;
			final int commitIndex;
			final int lastIndex; // just for later check
			synchronized (GUARD) {
				
				// only leaders perform heartbeats
				if (state != RaftState.LEADER) return; seenLeader.set(true); // its me
				
				// gather common info (from iteration to iteration may become rotten)
				term = persistentState.getCurrentTerm();
				prevLogIndex = nextIndexes.get(otherServer) - 1;
				prevLogTerm = persistentState.getTerm(prevLogIndex);
				entries = prevLogIndex > -1 ? persistentState.getLogEntries(prevLogIndex+1) : new ArrayList<LogEntry>();
				commitIndex = this.commitIndex;
				
				// last index if it is applied ok
				lastIndex = persistentState.getLastLogIndex();

				if (LOG_OPERATION) if (entries.size() > 0) {
					log("AppendEntriesTo:Send:"+ID(otherServer)+":prevLogIndex:"+prevLogIndex+":prevLogTerm:"+prevLogTerm+":count:"+entries.size()+":commitIndex:"+commitIndex);
				}
			}
			
			// send the message (and listen the answer) in concurrent
			executorQueue.execute(new Runnable() {
				
				@Override
				public void run() {
					try {
						AppendEntriesResponse response = 
						RMIsd.getInstance().appendEntries(otherServer, term, leaderId, prevLogIndex, prevLogTerm, entries, commitIndex);

						// not now?
						if (response.getTerm() != term) {
							if (LOG_LEADER) log("AppendEntriesTo:new term found:term:"+term);
							checkReceivedTerm(term);							
							return;
						}
						
						// execute inside the guard, any sent data could be changed and must be reevaluated
						synchronized (GUARD) {
							
							// still leader?
							if (state != RaftState.LEADER) { 
								if (LOG_LEADER) log("AppendEntriesTo:not leader any more");
								return; 
							}
							
							// term changed?
							if (term != persistentState.getCurrentTerm()) { 
								if (LOG_LEADER) log("AppendEntriesTo:term changed after receive message:term:"+term);
								return; 
							}
							
							// prevLogIndex changed?
							if (nextIndexes.get(otherServer) - 1 != prevLogIndex) { 
								if (LOG_LEADER) log("AppendEntriesTo:last index changed for server:nextIndex - 1:["+(nextIndexes.get(otherServer) - 1)+"]:prevLogIndex:["+prevLogIndex+"]");
								return; 
							}
							
							// prevLogTerm changed? 
							if (persistentState.getTerm(prevLogIndex) != prevLogTerm) { System.err.println("!prevLogTerm"); return; }
							// lastIndex decreased? 
							if (persistentState.getLastLogIndex() < lastIndex) { System.err.println("!lastIndex"); return; }
							
							// now it is safe, apply response
							if (response.isSucceeded()) {
								// update volatile server states
								nextIndexes.put(otherServer, lastIndex + 1);
								matchIndexes.put(otherServer, lastIndex);
							} else {
								// decrease nextIndexes by 1
								nextIndexes.put(otherServer, prevLogIndex - 1);
							}
							if (LOG_OPERATION) if (entries.size() > 0) { // 
								log("AppendEntriesTo:Response:"+ID(otherServer)+":success:"+response.isSucceeded()+":newPrevLogIndex:"+nextIndexes.get(otherServer));
							}
							
						}
						
					} catch (DSException e) {  						
					} catch (Exception e) {
						e.printStackTrace();
					}			
				}
			});	
		}
	}

	
	@Override
	public AppendEntriesResponse appendEntries(long term, String leaderId,
			int prevLogIndex, long prevLogTerm, List<LogEntry> entries,
			int leaderCommit) throws RemoteException {		
		if (!connected.get()) { return new AppendEntriesResponse(term, false); }
		
		checkReceivedTerm(term);
		synchronized (GUARD) {
			// there is a leader for term term
			if (term == persistentState.getCurrentTerm()) {
				seenLeader.set(true); 
				persistentState.setVotedFor(leaderId); // given a term, only one leader, only one true
			}
		}
		
		// compute the answer
		final long myTerm;
		final boolean success;
		synchronized (GUARD) {
			// get current status
			myTerm = persistentState.getCurrentTerm();
			final int myLastLogIndex = persistentState.getLastLogIndex();
			final long myPrevLogTerm = persistentState.getTerm(prevLogIndex);
			
			// is append entries valid for me?
			if (term < myTerm) {
				success = false;
			} else if (myLastLogIndex < prevLogIndex || myPrevLogTerm != prevLogTerm) {
				success = false;
			} else {
				success = true;
				
				// remove possible conflicting entries
				persistentState.deleteEntries(prevLogIndex + 1);

				// add new entries
				for (LogEntry entry : entries) {
					persistentState.appendEntry(entry);					
				}
				
				// update commitIndex (may be the server sent less things)
				commitIndex = Math.min(leaderCommit, persistentState.getLastLogIndex());
				
				// ### store in votedFor who is the leader
				persistentState.setVotedFor(leaderId);
			}

			if (LOG_OPERATION) if (entries.size() > 0) {
					log("AppendEntriesFrom:"+ID(leaderId)+":success:"+success+":term < myTerm:"+(term < myTerm)+":myLastLogIndex < prevLogIndex || myPrevLogTerm != prevLogTerm:"+(myLastLogIndex < prevLogIndex || myPrevLogTerm != prevLogTerm));
			}
		
		}
		
		// it is important to report myTerm, just if it is required to stepdown (because seenLeader.set to true)
		return new AppendEntriesResponse(myTerm, success);
	}

	//
	// COMMIT/APPLY OPERATIONS
	//
	// this is not really a timeout according to RAFT, 
	// but used because is related to heartbeat and because is cleaner
	protected void commitTimeout() {
		// compute new commitIndex (if Leader);
		synchronized (GUARD) {
			// if leader while candidate commit index available, check them
			// (this part can be dramatically optimized)
			boolean more = commitIndex < persistentState.getLastLogIndex();
			int nextIndex = commitIndex;
			if (state == RaftState.LEADER) while (more) {
				nextIndex = nextIndex + 1;

				// compute index matches for all other servers
				int matchCount = 0;
				int minimumMatchCount = (otherServers.size() + 1) / 2;
				for (Host otherServer : matchIndexes.keySet()) {
					int matchIndex = matchIndexes.get(otherServer);
					if (matchIndex >= nextIndex) { matchCount ++; }
				}
				
				if (LOG_OPERATION) log("Commiting:nextIndex:["+nextIndex+"]:matchCount:"+matchCount+">=?"+minimumMatchCount+":Indexes:"+matchIndexes);
				
				// check if we have a new candidate commit index 
				if (matchCount >= minimumMatchCount) {
					// check if the current commit index is from the current term
					if (persistentState.getTerm(nextIndex) == persistentState.getCurrentTerm()) {
						commitIndex = nextIndex;
					}
					more = nextIndex < persistentState.getLastLogIndex();
				} else {
					more = false;
				}
			}			
		}
		
		// apply operations
		synchronized (GUARD) {
			int count = 0;
			while (commitIndex > 0 && commitIndex > lastApplied) { count++;
				// we apply the next operation
				lastApplied++;
				
				// get operation
				LogEntry entry = persistentState.getLogEntry(lastApplied);
				Operation operation = entry.getCommand();
				
				// apply operation
				if (operation.getType() == OperationType.ADD) {
					this.addRecipe(((AddOperation) operation).getRecipe());
				} else if (operation.getType() == OperationType.REMOVE) {
					this.removeRecipe(((RemoveOperation) operation).getRecipeTitle());
				}
				if (LOG_CLIENT) log(operation, "Applyied:["+lastApplied+"]:commitIndex:["+commitIndex+"]");
			}
			if (count > 0) {
				// unblock blocked clients
				GUARD.notifyAll();
				if (LOG_CLIENT) log("WakingUp:["+lastApplied+"]:commitIndex:["+commitIndex+"]");
			}
		}
	}

	
	//
	// API 
	//

	@Override
	public RequestResponse Request(final Operation operation) throws RemoteException {
		if (LOG_CLIENT) log(operation, "Request:Arrived");
		
		// compute the response
		final String leader;
		final boolean success;
		synchronized (GUARD) {
			if (state == RaftState.LEADER) {
				// looksfor/adds the current operation to the log at given position
				int entryIndex = -1;
				long entryTerm = -1;
				int lastLogIndex = persistentState.getLastLogIndex();
				
				boolean entryFound = false;
				// is there previous operation with same client/timestamp in the log?				
				for (int index = 1; index <= lastLogIndex && !entryFound; index++) {
					LogEntry otherEntry = persistentState.getLogEntry(index);
					if (otherEntry.getCommand().equals(operation)) {
						entryFound = true;
						entryIndex = index;
						entryTerm = persistentState.getTerm(index);
						if (LOG_CLIENT) log(operation, "Request:FoundAt:["+index+"]");
					}
				}
				
				// is not previous, so it is added
				if (!entryFound) {
					persistentState.addEntry(operation);
					entryIndex = lastLogIndex = persistentState.getLastLogIndex();
					entryTerm = persistentState.getLastLogTerm();
					if (LOG_CLIENT) log(operation, "Request:Enqueued...");					
				}
				
				
				// wait for completeness
				while (lastApplied < entryIndex &&                       // it is not applied						
						persistentState.getTerm(entryIndex) == entryTerm // and my term is correct (and no entries removed)
						) {
					if (LOG_CLIENT) log(operation, "Request:Waiting:lastApplied < entryIndex:"+(lastApplied < entryIndex)+":persistentState.getTerm(entryIndex) == entryTerm:"+(persistentState.getTerm(entryIndex) == entryTerm));
					
					try { GUARD.wait();	} catch (InterruptedException e) { }
				}
												
				// success if the entry is mine
				leader = getLeaderUnguarded();
				success = persistentState.getTerm(entryIndex) == entryTerm;
				
				if (LOG_CLIENT) log(operation, "Request:Succeed?:"+success+":leader:"+ID(leader));
			} else {
				// redirect response
				leader = getLeaderUnguarded();
				success = false;
				if (LOG_CLIENT) { // && entries.size() > 0
					log(operation, "Request:RedirectTo:"+ID(leader)+":success:"+false);
				}
			}
		}
		
		return new RequestResponse(leader, success);
	}
	
	private String getLeaderUnguarded() {
		String leader = persistentState.getVotedFor();
		if (leader == null) {
			// a random possible leader
			int otherIndex = new Random().nextInt(otherServers.size());
			leader = otherServers.get(otherIndex).getId();
		}
		return leader;
	}

	
	//
	// Other methods
	//
	public String getServerId(){
		return localHost.getId();
	}

	public synchronized List<LogEntry> getLog(){
		return persistentState.getLog();
	}

	public long getCurrentTerm() {
		return persistentState.getCurrentTerm();
	}

	public String getLeaderId() {
		return persistentState.getVotedFor();
	}
}
