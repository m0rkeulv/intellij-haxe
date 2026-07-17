package debug.session;

import debug.Pointer;
import debug.module.CodeGraph;
import debug.target.WaitOutcome;

import haxe.Int64;

/**
	Source-level stepping (next / stepIn / stepOut / smart step into): computes
	the landings for a step from the CFG, plants the temporary INT3s, and
	classifies step-temp hits (foreign threads, the recursion frame guard).

	A friend of DebugSession (@:access): it drives the session's trap machinery
	(stepOverAndResume, stepPastTempAndResume, enterStopped) and owns the
	session's `activeStep` transitions; the session routes step commands and
	step-temp trap hits here.
**/
@:access(debug.session.DebugSession)
class SteppingController {
	final session:DebugSession;

	public function new(session:DebugSession) {
		this.session = session;
	}

	public function handleStep(requestSeq:Int, threadId:Int, mode:StepMode, targetId:Null<Int>):Void {
		switch (session.state) {
			case Stopped(_):
				var interrupted = planStep(threadId, mode, targetId);
				session.state = Running;
				session.emit(EvStepStarted(requestSeq)); // ack now; the stopped(reason:"step") event follows
				if (interrupted != null) {
					// another thread stopped us during the resume dance: report
					// that stop right after the step response
					session.handleWaitOutcome(interrupted);
				} else if (session.activeStep == null) {
					// No landing could be planted at all (the only "next" is an
					// unresolvable native return). Behave like continue and tell
					// the client we are running rather than leaving it waiting
					// for a step stop that cannot exist. NOTE: a step whose
					// landings ARE planted waits for them however long the code
					// runs (a slow call is not a reason to give up the step).
					session.emit(EvResumed(threadId));
				}
			default:
				session.reject(requestSeq, "Cannot step: debuggee is not stopped");
		}
	}

	public function handleStepInTargets(requestSeq:Int, frameId:Int):Void {
		switch (session.state) {
			case Stopped(threadId):
				session.emit(EvStepInTargets(requestSeq, computeStepInTargets(frameId, threadId)));
			default:
				session.reject(requestSeq, "Cannot list step-in targets: debuggee is not stopped");
		}
	}

	// The calls on `frameId`'s stopped line, as smart-step-into choices. Only the
	// newest frame can step, so any other frame gets an empty list (not an error:
	// the client asks per its UI state). Unresolvable callees (closures, virtual
	// dispatch through a vtable) are omitted — the plain stepIn still enters them.
	function computeStepInTargets(frameId:Int, threadId:Int):Array<StepInTargetInfo> {
		var frame = session.inspector.frameAt(frameId);
		if (frame == null || frame.index != 0) {
			return [];
		}
		var fidx = frame.location.fidx;
		var startOp = frame.location.op;
		var startLine = session.module.lineOf(fidx, startOp);
		var graph = new CodeGraph(session.module.opcodes(fidx));
		var eip = session.api.readRegister(session.debuggeePid, threadId, Eip);
		var targets = graph.stepTargets(startOp, startLine, (op) -> session.module.lineOf(fidx, op),
			callAtOpAlreadyRan(eip, fidx, startOp));
		var callOps = targets.callOps.copy();
		callOps.sort((a, b) -> a - b); // the CFG walk is DFS; present in execution order
		var result:Array<StepInTargetInfo> = [];
		for (op in callOps) {
			var callee = session.module.callTargetFunction(fidx, op);
			if (callee >= 0) {
				result.push({id: op, label: session.module.functionName(callee)});
			}
		}
		return result;
	}

	// Plant the temporary breakpoints that mark where this step should land, then
	// resume (stepping over the instruction we are parked on). `activeStep`
	// afterwards says whether any landing was planted (null = the caller
	// downgrades the step to a plain continue). Returns a pending debug event
	// when another thread interrupted the resume dance.
	// `targetId` (stepIn only): enter ONLY the call at that opcode (a smart step
	// into choice from stepInTargets); the line-change/return landings stay
	// planted as a fallback, so a selected call that never executes (short
	// circuit, conditional) degrades to a step-over stop instead of running away.
	function planStep(threadId:Int, mode:StepMode, targetId:Null<Int>):Null<WaitOutcome> {
		session.breakpoints.clearTemps();
		session.activeStep = null;
		var startEsp = session.api.readRegister(session.debuggeePid, threadId, Esp);

		var eip = session.api.readRegister(session.debuggeePid, threadId, Eip);
		var position = session.jit.resolveAddress(eip);
		if (position == null) {
			// not in known bytecode (e.g. inside a native call): can't compute targets
			return session.stepOverAndResume(threadId);
		}
		var fidx = position.fidx;
		var startLine = session.module.lineOf(fidx, position.op);
		var graph = new CodeGraph(session.module.opcodes(fidx));
		var targets = graph.stepTargets(position.op, startLine, (op) -> session.module.lineOf(fidx, op),
			callAtOpAlreadyRan(eip, fidx, position.op));
		var returnAddress = currentReturnAddress(threadId);
		var targetedEntry:Null<Pointer> = null;
		var targetedCallSite:Null<{fidx:Int, op:Int}> = null;

		if (mode == StepOut) {
			// step out: stop only when the current function returns
			if (returnAddress != null) {
				session.breakpoints.addTemp(returnAddress);
			}
		} else {
			for (op in targets.lineChangeOps) {
				session.breakpoints.addTemp(session.jit.addressOf(fidx, op));
			}
			if (targets.returns && returnAddress != null) {
				session.breakpoints.addTemp(returnAddress);
			}
			if (mode == StepIn) {
				for (op in targets.callOps) {
					if (targetId != null && op != targetId) {
						continue; // targeted step: only the chosen call's entry
					}
					var callee = session.module.callTargetFunction(fidx, op);
					if (callee >= 0) {
						var entry = session.jit.addressOf(callee, 0); // callee entry = first opcode
						session.breakpoints.addTemp(entry);
						if (targetId != null) {
							targetedEntry = entry;
							targetedCallSite = {fidx: fidx, op: op};
						}
					}
				}
			}
		}

		if (session.breakpoints.hasTemps()) {
			session.activeStep = {
				threadId: threadId, mode: mode, startEsp: startEsp,
				targetEntry: targetedEntry, targetCallSite: targetedCallSite
			};
		}
		return session.stepOverAndResume(threadId);
	}

	function currentReturnAddress(threadId:Int):Null<Pointer> {
		var frames = session.stackWalker.walk(threadId);
		return frames.length >= 2 ? frames[1].address : null;
	}

	// Parked MID-op — EIP past the op's first native byte — means the op's call
	// instruction already ran and we sit at its return address (the only
	// user-visible mid-op stop: temps/user breakpoints are planted at op
	// starts). That call must not be offered or planted as enterable again.
	function callAtOpAlreadyRan(eip:Pointer, fidx:Int, op:Int):Bool {
		return Int64.compare(eip, session.jit.addressOf(fidx, op)) > 0;
	}

	// A temporary (step) breakpoint. Temps live at CODE addresses, so any
	// thread executing that line traps: a hit by a thread that does NOT own
	// the step is never its landing — step that thread past and keep going.
	// The owning thread also honours the recursion frame guard (step over/out).
	public function handleTempHit(threadId:Int, hitAddress:Pointer):Void {
		var step = session.activeStep;
		if (step != null && (threadId != step.threadId || !frameGuardSatisfied(step))) {
			session.stepPastTempAndResume(threadId, hitAddress);
			return;
		}
		if (step != null && !targetedCallSiteSatisfied(step, threadId, hitAddress)) {
			session.stepPastTempAndResume(threadId, hitAddress);
			return;
		}
		session.enterStopped(threadId, null);
		session.emit(EvStoppedStep(threadId));
	}

	// A targeted step-in's entry temp is at the callee FUNCTION, which the line
	// may invoke more than once (cfg.test1(1)...test1(2)): the landing is ours
	// only when the new frame's return address points back at the CHOSEN call
	// op. Non-entry landings (line change, return fallback) and untargeted
	// steps are always valid.
	function targetedCallSiteSatisfied(step:ActiveStep, threadId:Int, hitAddress:Pointer):Bool {
		if (step.targetEntry == null || Int64.compare(hitAddress, step.targetEntry) != 0) {
			return true;
		}
		var site = step.targetCallSite;
		if (site == null) {
			return true;
		}
		var frames = session.stackWalker.walk(threadId);
		if (frames.length < 2) {
			return false; // no caller frame: cannot be the chosen call site
		}
		// resolve one byte BEFORE the return address: that is always inside the
		// call instruction's op, while the return address itself can fall on the
		// next op's boundary (a call whose op emits nothing after the call)
		var caller = session.jit.resolveAddress(Int64.sub(frames[1].address, Int64.ofInt(1)));
		return caller != null && caller.fidx == site.fidx && caller.op == site.op;
	}

	// Stack grows down: a shallower-or-equal frame has esp >= the step-start
	// esp. Only meaningful for the step's OWN thread — every thread has its own
	// stack, so comparing another thread's esp against step.startEsp is noise
	// (foreign temp hits are filtered out before this is consulted).
	function frameGuardSatisfied(step:ActiveStep):Bool {
		if (step.mode == StepIn) {
			return true; // any landing (same-frame line change or callee entry) is valid
		}
		var esp = session.api.readRegister(session.debuggeePid, step.threadId, Esp);
		return Int64.compare(esp, step.startEsp) >= 0;
	}
}
