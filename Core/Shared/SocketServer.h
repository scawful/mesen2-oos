#pragma once
#include "pch.h"
#include "Utilities/SimpleLock.h"
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

class SocketServer {
private:
	Emulator* _emu;
	unique_ptr<thread> _serverThread;
	atomic<bool> _running;
	int _serverFd = -1;
	string _socketPath;
	SimpleLock _lock;

	unordered_map<string, CommandHandler> _handlers;

	void ServerLoop();
	void HandleClient(int clientFd);
	SocketCommand ParseCommand(const string& json);
	void RegisterHandlers();

	// Built-in command handlers
	static SocketResponse HandlePing(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleState(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandlePause(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleResume(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleReset(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleRead(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleRead16(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleWrite(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleWrite16(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleReadBlock(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleSaveState(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleLoadState(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleLoadScript(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleScreenshot(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleGetCpuState(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleSetInput(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleDisasm(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleStep(Emulator* emu, const SocketCommand& cmd);
	static SocketResponse HandleRunFrame(Emulator* emu, const SocketCommand& cmd);

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
