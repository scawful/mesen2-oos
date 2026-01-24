#pragma once
#include "pch.h"
#include "Utilities/SimpleLock.h"
#include "Shared/MemoryType.h"
#include "Shared/CpuType.h"
#include <thread>
#include <atomic>
#include <functional>
#include <unordered_map>
#include <deque>

class Emulator;

// JSON-like command structure (simple key-value parsing)
struct SocketCommand {
	string type;
	unordered_map<string, string> params;
};

struct SocketResponse {
	bool success;
	string data;
	string error;

	string ToJson() const;
};

// Command handler type
using CommandHandler = std::function<SocketResponse(Emulator*, const SocketCommand&)>;

// Memory snapshot for diff operations
struct MemorySnapshot {
	string name;
	vector<uint8_t> data;
	uint32_t memoryType;
	uint64_t timestamp;
};

// P register change tracking for debugging processor status
struct PRegisterChange {
	uint32_t pc;           // Full 24-bit address (K:PC)
	uint8_t oldP;          // P before instruction
	uint8_t newP;          // P after instruction
	uint8_t opcode;        // Instruction that caused change
	uint64_t cycleCount;   // For timeline correlation
};

// Memory write attribution for debugging memory corruption
struct MemoryWriteRecord {
	uint32_t pc;           // PC that executed the write
	uint32_t addr;         // Address written to
	uint16_t value;        // Value written (8 or 16 bit)
	uint8_t size;          // 1 or 2 bytes
	uint64_t cycleCount;   // Timing
	uint16_t stackPointer; // SP at time of write (for call stack depth)
};

// Memory watch region for tracking writes
struct MemoryWatchRegion {
	uint32_t id;
	uint32_t startAddr;
	uint32_t endAddr;
	uint32_t maxDepth;
};

// Symbol table entry for Oracle debugging
struct SymbolEntry {
	string name;
	uint32_t addr;
	uint8_t size;          // 1, 2, or 3 bytes
	string type;           // "byte", "word", "long"
};

// Breakpoint info for socket API
struct SocketBreakpoint {
	uint32_t id;
	CpuType cpuType;
	MemoryType memoryType;
	uint8_t type;  // BreakpointTypeFlags
	int32_t startAddr;
	int32_t endAddr;
	bool enabled;
	string condition;
};

// Logpoint: breakpoint that logs without halting
struct SocketLogpoint {
	uint32_t id;
	CpuType cpuType;
	int32_t addr;
	bool enabled;
	string expression;  // Expression to evaluate and log
};

// Logpoint hit record
struct LogpointHit {
	uint32_t logpointId;
	uint32_t pc;
	CpuType cpuType;
	uint64_t cycleCount;
	string value;  // Evaluated expression result
};

class SocketServer {
private:
	Emulator* _emu;
	unique_ptr<thread> _serverThread;
	atomic<bool> _running;
	int _serverFd = -1;
	string _socketPath;
	SimpleLock _lock;

	unordered_map<string, CommandHandler> _handlers;

	// Memory snapshots for diff operations (static for use in static handlers)
	static unordered_map<string, MemorySnapshot> _snapshots;
	static SimpleLock _snapshotLock;

	// Breakpoint management (static for use in static handlers)
	static vector<SocketBreakpoint> _breakpoints;
	static uint32_t _nextBreakpointId;
	static SimpleLock _breakpointLock;

	// P register change tracking (static for use in static handlers)
	static std::deque<PRegisterChange> _pRegisterLog;
	static uint32_t _pRegisterLogMaxSize;
	static bool _pRegisterWatchEnabled;
	static uint8_t _lastPRegister;
	static SimpleLock _pRegisterLock;

	// Memory write attribution (static for use in static handlers)
	static vector<MemoryWatchRegion> _memoryWatches;
	static unordered_map<uint32_t, std::deque<MemoryWriteRecord>> _memoryWriteLog;
	static uint32_t _nextMemoryWatchId;
	static SimpleLock _memoryWatchLock;

	// Symbol table (static for use in static handlers)
	static unordered_map<string, SymbolEntry> _symbolTable;
	static SimpleLock _symbolLock;

	// Helper to sync breakpoints with emulator
	static void SyncBreakpoints(Emulator* emu);

	void ServerLoop();
	void HandleClient(int clientFd);
	bool ParseCommand(const string& json, SocketCommand& cmd, string& error);
	void RegisterHandlers();

	// Built-in command handlers
	static SocketResponse HandlePing(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleState(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleHealth(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandlePause(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleResume(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleReset(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleRead(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleRead16(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleWrite(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleWrite16(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleReadBlock(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleWriteBlock(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleSaveState(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleLoadState(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleLoadScript(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleScreenshot(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleGetCpuState(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleStateInspector(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleSetInput(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleDisasm(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleStep(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleRunFrame(Emulator* emu, const SocketCommand& cmd);

	// Emulation control handlers
	static SocketResponse HandleRomInfo(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleRewind(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleCheat(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleSpeed(Emulator* emu, const SocketCommand& cmd);

	// Memory analysis handlers
	static SocketResponse HandleSearch(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleSnapshot(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleDiff(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleLabels(Emulator* emu, const SocketCommand& cmd);

	// Breakpoint handlers
	static SocketResponse HandleBreakpoint(Emulator* emu, const SocketCommand& cmd);

	// Batch handler
	static SocketResponse HandleBatch(Emulator* emu, const SocketCommand& cmd);

	// Trace handler
	static SocketResponse HandleTrace(Emulator* emu, const SocketCommand& cmd);

	// P register tracking handlers
	static SocketResponse HandlePWatch(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandlePLog(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandlePAssert(Emulator* emu, const SocketCommand& cmd);

	// Memory write attribution handlers
	static SocketResponse HandleMemWatchWrites(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleMemBlame(Emulator* emu, const SocketCommand& cmd);

	// Symbol table handlers
	static SocketResponse HandleSymbolsLoad(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleSymbolsResolve(Emulator* emu, const SocketCommand& cmd);

	// Collision overlay handlers
	static SocketResponse HandleCollisionOverlay(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleCollisionDump(Emulator* emu, const SocketCommand& cmd);

public:
	SocketServer(Emulator* emu);
	~SocketServer();

	void Start();
	void Stop();
	bool IsRunning() const { return _running; }
	string GetSocketPath() const { return _socketPath; }

	// Register custom command handler
	void RegisterHandler(const string& command, CommandHandler handler);

	// Debugger hook methods - called from SnesDebugger to log events
	static void LogPRegisterChange(uint32_t pc, uint8_t oldP, uint8_t newP, uint8_t opcode, uint64_t cycleCount);
	static void LogMemoryWrite(uint32_t pc, uint32_t addr, uint16_t value, uint8_t size, uint64_t cycleCount, uint16_t stackPointer);
	static bool IsPRegisterWatchEnabled() { return _pRegisterWatchEnabled; }
	static bool HasMemoryWatch(uint32_t addr);
};
