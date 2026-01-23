#pragma once
#include "pch.h"
#include "Utilities/SimpleLock.h"
#include "Shared/MemoryType.h"
#include "Shared/CpuType.h"
#include <thread>
#include <atomic>
#include <functional>
#include <unordered_map>

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

	// Helper to sync breakpoints with emulator
	static void SyncBreakpoints(Emulator* emu);

	void ServerLoop();
	void HandleClient(int clientFd);
	SocketCommand ParseCommand(const string& json);
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

public:
	SocketServer(Emulator* emu);
	~SocketServer();

	void Start();
	void Stop();
	bool IsRunning() const { return _running; }
	string GetSocketPath() const { return _socketPath; }

	// Register custom command handler
	void RegisterHandler(const string& command, CommandHandler handler);
};
