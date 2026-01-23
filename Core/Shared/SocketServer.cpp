#include "pch.h"
#include "SocketServer.h"
#include "Emulator.h"
#include "DebuggerRequest.h"
#include "SaveStateManager.h"
#include "EmuSettings.h"
#include "MessageManager.h"
#include "RewindManager.h"
#include "CheatManager.h"
#include "RomInfo.h"
#include "Core/Debugger/Debugger.h"
#include "Core/Debugger/ScriptManager.h"
#include "Core/Debugger/DebugTypes.h"
#include "Core/Debugger/Disassembler.h"
#include "Core/Debugger/MemoryDumper.h"
#include "Core/Debugger/LabelManager.h"
#include "Core/Debugger/Breakpoint.h"
#include "Core/Debugger/BreakpointManager.h"
#include "Core/Debugger/DebugUtilities.h"
#include "SNES/SnesCpuTypes.h"
#include "Shared/TimingInfo.h"
#include "Shared/Video/VideoDecoder.h"
#include "Shared/Video/VideoRenderer.h"
#include "Utilities/HexUtilities.h"
#include "Utilities/VirtualFile.h"
#include "SNES/SnesPpuTypes.h"
#include "SNES/SpcTypes.h"
#include "SNES/Coprocessors/DSP/NecDspTypes.h"
#include "SNES/Coprocessors/GSU/GsuTypes.h"
#include "SNES/Coprocessors/CX4/Cx4Types.h"

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <poll.h>
#include <chrono>
#include <cctype>
#include <algorithm>
#include <cerrno>
#include <fcntl.h>

using std::hex;
using std::dec;
using std::setw;
using std::setfill;
using std::fixed;
using std::uppercase;
using std::setprecision;
using std::make_unique;

// Static member definitions for memory snapshots
unordered_map<string, MemorySnapshot> SocketServer::_snapshots;
SimpleLock SocketServer::_snapshotLock;

// Static member definitions for breakpoints
vector<SocketBreakpoint> SocketServer::_breakpoints;
uint32_t SocketServer::_nextBreakpointId = 1;
SimpleLock SocketServer::_breakpointLock;

// Forward declarations for helper functions
static string NormalizeKey(string value);
static bool TryParseMemoryType(const string& memtype, MemoryType& outType);
static string JsonEscape(const string& value);
static string FormatHex(uint64_t value, int width);
static string FormatSnesFlags(const SnesCpuState& cpu);
static string CpuTypeName(CpuType cpuType);
static void AppendCpuStateJson(stringstream& ss, CpuType cpuType, Debugger* debugger);
static string Trim(string value);
static bool ReadRequestLine(int clientFd, std::atomic<bool>& running, string& out, string& error);
static bool ParseJsonObject(const string& json, unordered_map<string, string>& out, string& error);
static bool ParseJsonString(const string& json, size_t& index, string& out, string& error);
static void AppendUtf8(string& out, uint32_t codepoint);

string SocketResponse::ToJson() const {
	stringstream ss;
	ss << "{\"success\":" << (success ? "true" : "false");
	if (!data.empty()) {
		ss << ",\"data\":" << data;
	}
	if (!error.empty()) {
		ss << ",\"error\":\"" << JsonEscape(error) << "\"";
	}
	ss << "}";
	return ss.str();
}

SocketServer::SocketServer(Emulator* emu) : _emu(emu), _running(false) {
	// Create socket path with PID for uniqueness
	_socketPath = "/tmp/mesen2-" + std::to_string(getpid()) + ".sock";
	RegisterHandlers();
}

SocketServer::~SocketServer() {
	Stop();
}

void SocketServer::RegisterHandlers() {
	_handlers["PING"] = HandlePing;
	_handlers["STATE"] = HandleState;
	_handlers["HEALTH"] = HandleHealth;
	_handlers["PAUSE"] = HandlePause;
	_handlers["RESUME"] = HandleResume;
	_handlers["RESET"] = HandleReset;
	_handlers["READ"] = HandleRead;
	_handlers["READ16"] = HandleRead16;
	_handlers["WRITE"] = HandleWrite;
	_handlers["WRITE16"] = HandleWrite16;
	_handlers["READBLOCK"] = HandleReadBlock;
	_handlers["WRITEBLOCK"] = HandleWriteBlock;
	_handlers["SAVESTATE"] = HandleSaveState;
	_handlers["LOADSTATE"] = HandleLoadState;
	_handlers["LOADSCRIPT"] = HandleLoadScript;
	_handlers["SCREENSHOT"] = HandleScreenshot;
	_handlers["CPU"] = HandleGetCpuState;
	_handlers["STATEINSPECT"] = HandleStateInspector;
	_handlers["INPUT"] = HandleSetInput;
	_handlers["DISASM"] = HandleDisasm;
	_handlers["STEP"] = HandleStep;
	_handlers["FRAME"] = HandleRunFrame;
	_handlers["ROMINFO"] = HandleRomInfo;
	_handlers["REWIND"] = HandleRewind;
	_handlers["CHEAT"] = HandleCheat;
	_handlers["SPEED"] = HandleSpeed;
	_handlers["SEARCH"] = HandleSearch;
	_handlers["SNAPSHOT"] = HandleSnapshot;
	_handlers["DIFF"] = HandleDiff;
	_handlers["LABELS"] = HandleLabels;
	_handlers["BREAKPOINT"] = HandleBreakpoint;
}

void SocketServer::RegisterHandler(const string& command, CommandHandler handler) {
	auto lock = _lock.AcquireSafe();
	_handlers[command] = handler;
}

void SocketServer::Start() {
	if (_running) return;

	// Remove existing socket file
	unlink(_socketPath.c_str());

	// Create Unix domain socket
	_serverFd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (_serverFd < 0) {
		MessageManager::Log("[SocketServer] Failed to create socket");
		return;
	}

	struct sockaddr_un addr;
	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	strncpy(addr.sun_path, _socketPath.c_str(), sizeof(addr.sun_path) - 1);

	if (bind(_serverFd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
		MessageManager::Log("[SocketServer] Failed to bind socket: " + _socketPath);
		close(_serverFd);
		_serverFd = -1;
		return;
	}

	if (listen(_serverFd, 5) < 0) {
		MessageManager::Log("[SocketServer] Failed to listen on socket");
		close(_serverFd);
		_serverFd = -1;
		return;
	}

	_running = true;
	_serverThread = make_unique<thread>(&SocketServer::ServerLoop, this);

	MessageManager::Log("[SocketServer] Started on " + _socketPath);
}

void SocketServer::Stop() {
	if (!_running) return;

	_running = false;

	// Close server socket to unblock accept()
	if (_serverFd >= 0) {
		shutdown(_serverFd, SHUT_RDWR);
		close(_serverFd);
		_serverFd = -1;
	}

	if (_serverThread && _serverThread->joinable()) {
		_serverThread->join();
	}
	_serverThread.reset();

	// Remove socket file
	unlink(_socketPath.c_str());

	MessageManager::Log("[SocketServer] Stopped");
}

void SocketServer::ServerLoop() {
	while (_running) {
		struct pollfd pfd;
		pfd.fd = _serverFd;
		pfd.events = POLLIN;

		int ret = poll(&pfd, 1, 100); // 100ms timeout
		if (ret < 0) {
			if (errno != EINTR) {
				break;
			}
			continue;
		}

		if (ret == 0) continue; // Timeout, check _running flag

		if (pfd.revents & POLLIN) {
			int clientFd = accept(_serverFd, nullptr, nullptr);
			if (clientFd >= 0) {
				HandleClient(clientFd);
				close(clientFd);
			}
		}
	}
}

void SocketServer::HandleClient(int clientFd) {
	string request;
	string readError;
	if(!ReadRequestLine(clientFd, _running, request, readError)) {
		if(!readError.empty()) {
			SocketResponse response;
			response.success = false;
			response.error = readError;
			string responseJson = response.ToJson() + "\n";
			write(clientFd, responseJson.c_str(), responseJson.size());
		}
		return;
	}

	SocketCommand cmd;
	string parseError;
	if(!ParseCommand(request, cmd, parseError)) {
		SocketResponse response;
		response.success = false;
		response.error = parseError.empty() ? "Invalid request" : parseError;
		string responseJson = response.ToJson() + "\n";
		write(clientFd, responseJson.c_str(), responseJson.size());
		return;
	}

	CommandHandler handler;
	{
		auto lock = _lock.AcquireSafe();
		auto it = _handlers.find(cmd.type);
		if(it != _handlers.end()) {
			handler = it->second;
		}
	}

	SocketResponse response;
	if(handler) {
		try {
			response = handler(_emu, cmd);
		} catch (const std::exception& e) {
			response.success = false;
			response.error = e.what();
		}
	} else {
		response.success = false;
		response.error = "Unknown command: " + cmd.type;
	}

	string responseJson = response.ToJson() + "\n";
	write(clientFd, responseJson.c_str(), responseJson.size());
}

bool SocketServer::ParseCommand(const string& json, SocketCommand& cmd, string& error) {
	cmd.type.clear();
	cmd.params.clear();

	unordered_map<string, string> params;
	if(!ParseJsonObject(json, params, error)) {
		return false;
	}

	auto typeIt = params.find("type");
	if(typeIt == params.end()) {
		error = "Missing type field";
		return false;
	}

	cmd.type = Trim(typeIt->second);
	if(cmd.type.empty()) {
		error = "Missing command type";
		return false;
	}

	std::transform(cmd.type.begin(), cmd.type.end(), cmd.type.begin(), [](unsigned char c) {
		return static_cast<char>(std::toupper(c));
	});

	params.erase(typeIt);
	cmd.params = std::move(params);
	return true;
}

// ============================================================================
// Command Handlers
// ============================================================================

SocketResponse SocketServer::HandlePing(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	resp.success = true;
	resp.data = "\"PONG\"";
	return resp;
}

SocketResponse SocketServer::HandleState(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	stringstream ss;
	ss << "{";
	ss << "\"running\":" << (emu->IsRunning() ? "true" : "false") << ",";
	ss << "\"paused\":" << (emu->IsPaused() ? "true" : "false") << ",";
	ss << "\"frame\":" << emu->GetFrameCount() << ",";
	ss << "\"fps\":" << fixed << setprecision(2) << emu->GetFps() << ",";
	ss << "\"consoleType\":" << static_cast<int>(emu->GetConsoleType());

	// If debugging, get more state
	if (emu->IsDebugging()) {
		auto dbg = emu->GetDebugger(false);
		if (dbg.GetDebugger()) {
			// Could add breakpoint count, etc.
			ss << ",\"debugging\":true";
		}
	}

	ss << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleHealth(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	bool running = emu->IsRunning();
	bool paused = emu->IsPaused();
	bool debugging = emu->IsDebugging();

	string watchHudText;
	if(running && emu->GetVideoRenderer()) {
		watchHudText = emu->GetVideoRenderer()->GetWatchHudText();
	}

	bool disasmOk = false;
	string disasmData = "null";
	string pcValue = "null";
	CpuType cpuType = CpuType::Snes;

	if(running) {
		auto dbg = emu->GetDebugger(true);
		if(dbg.GetDebugger()) {
			auto cpuTypes = emu->GetCpuTypes();
			if(!cpuTypes.empty()) {
				cpuType = cpuTypes[0];
			}

			uint32_t pc = dbg.GetDebugger()->GetProgramCounter(cpuType, true);
			pcValue = "\"" + FormatHex(pc, DebugUtilities::GetProgramCounterSize(cpuType)) + "\"";

			SocketCommand disasmCmd;
			disasmCmd.type = "DISASM";
			disasmCmd.params["addr"] = FormatHex(pc, DebugUtilities::GetProgramCounterSize(cpuType));
			disasmCmd.params["count"] = "1";

			SocketResponse disasmResp = HandleDisasm(emu, disasmCmd);
			disasmOk = disasmResp.success;
			if(disasmResp.success) {
				disasmData = disasmResp.data;
			}
		}
	}

	stringstream ss;
	ss << "{";
	ss << "\"running\":" << (running ? "true" : "false") << ",";
	ss << "\"paused\":" << (paused ? "true" : "false") << ",";
	ss << "\"debugging\":" << (debugging ? "true" : "false") << ",";
	ss << "\"consoleType\":" << static_cast<int>(emu->GetConsoleType()) << ",";
	ss << "\"cpuType\":" << (running ? std::to_string(static_cast<int>(cpuType)) : "null") << ",";
	ss << "\"pc\":" << pcValue << ",";
	ss << "\"disasmOk\":" << (disasmOk ? "true" : "false") << ",";
	ss << "\"disasm\":" << (disasmOk ? disasmData : "null") << ",";
	ss << "\"watchHudText\":\"" << JsonEscape(watchHudText) << "\"";
	ss << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandlePause(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	emu->Pause();
	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleResume(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	emu->Resume();
	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleReset(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	emu->Reset();
	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleRead(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto it = cmd.params.find("addr");
	if (it == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing addr parameter";
		return resp;
	}

	uint32_t addr = 0;
	string addrStr = it->second;
	if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
		addr = std::stoul(addrStr.substr(2), nullptr, 16);
	} else {
		addr = std::stoul(addrStr, nullptr, 16);
	}

	// Read from memory (default to SNES memory map)
	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	MemoryType memType = MemoryType::SnesMemory;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
	}

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(memType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}
	if(addr >= memSize) {
		resp.success = false;
		resp.error = "Address out of range";
		return resp;
	}

	uint8_t value = dumper->GetMemoryValue(memType, addr);

	stringstream ss;
	ss << "\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)value << "\"";
	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleRead16(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto it = cmd.params.find("addr");
	if (it == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing addr parameter";
		return resp;
	}

	uint32_t addr = 0;
	string addrStr = it->second;
	if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
		addr = std::stoul(addrStr.substr(2), nullptr, 16);
	} else {
		addr = std::stoul(addrStr, nullptr, 16);
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	MemoryType memType = MemoryType::SnesMemory;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
	}

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(memType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}
	if(addr >= memSize || addr + 1 >= memSize) {
		resp.success = false;
		resp.error = "Address out of range";
		return resp;
	}

	uint8_t lo = dumper->GetMemoryValue(memType, addr);
	uint8_t hi = dumper->GetMemoryValue(memType, addr + 1);
	uint16_t value = lo | (hi << 8);

	stringstream ss;
	ss << "\"0x" << hex << uppercase << setw(4) << setfill('0') << value << "\"";
	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleWrite(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto addrIt = cmd.params.find("addr");
	auto valIt = cmd.params.find("value");
	if (valIt == cmd.params.end()) {
		valIt = cmd.params.find("val");
	}

	if (addrIt == cmd.params.end() || valIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing addr or value parameter";
		return resp;
	}

	uint32_t addr = 0;
	string addrStr = addrIt->second;
	if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
		addr = std::stoul(addrStr.substr(2), nullptr, 16);
	} else {
		addr = std::stoul(addrStr, nullptr, 16);
	}

	uint8_t value = 0;
	string valStr = valIt->second;
	if (valStr.substr(0, 2) == "0x" || valStr.substr(0, 2) == "0X") {
		value = std::stoul(valStr.substr(2), nullptr, 16);
	} else {
		value = std::stoul(valStr);
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	MemoryType memType = MemoryType::SnesMemory;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
	}

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(memType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}
	if(addr >= memSize) {
		resp.success = false;
		resp.error = "Address out of range";
		return resp;
	}

	dumper->SetMemoryValue(memType, addr, value, false);

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleWrite16(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto addrIt = cmd.params.find("addr");
	auto valIt = cmd.params.find("value");
	if (valIt == cmd.params.end()) {
		valIt = cmd.params.find("val");
	}

	if (addrIt == cmd.params.end() || valIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing addr or value parameter";
		return resp;
	}

	uint32_t addr = 0;
	string addrStr = addrIt->second;
	if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
		addr = std::stoul(addrStr.substr(2), nullptr, 16);
	} else {
		addr = std::stoul(addrStr, nullptr, 16);
	}

	uint16_t value = 0;
	string valStr = valIt->second;
	if (valStr.substr(0, 2) == "0x" || valStr.substr(0, 2) == "0X") {
		value = std::stoul(valStr.substr(2), nullptr, 16);
	} else {
		value = std::stoul(valStr);
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	MemoryType memType = MemoryType::SnesMemory;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
	}

	uint32_t memSize = dumper->GetMemorySize(memType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}
	if(addr >= memSize || addr + 1 >= memSize) {
		resp.success = false;
		resp.error = "Address out of range";
		return resp;
	}

	dumper->SetMemoryValue(memType, addr, value & 0xFF, false);
	dumper->SetMemoryValue(memType, addr + 1, (value >> 8) & 0xFF, false);

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleReadBlock(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto addrIt = cmd.params.find("addr");
	auto lenIt = cmd.params.find("len");
	if (lenIt == cmd.params.end()) {
		lenIt = cmd.params.find("length");
	}

	if (addrIt == cmd.params.end() || lenIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing addr or len parameter";
		return resp;
	}

	uint32_t addr = 0;
	string addrStr = addrIt->second;
	if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
		addr = std::stoul(addrStr.substr(2), nullptr, 16);
	} else {
		addr = std::stoul(addrStr, nullptr, 16);
	}

	uint32_t len = std::stoul(lenIt->second);
	if (len > 0x10000) len = 0x10000; // Limit to 64KB

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	MemoryType memType = MemoryType::SnesMemory;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
	}

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(memType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}
	if(addr >= memSize) {
		resp.success = false;
		resp.error = "Address out of range";
		return resp;
	}
	if(addr + len > memSize) {
		len = memSize - addr;
	}

	stringstream ss;
	ss << "\"";
	for (uint32_t i = 0; i < len; i++) {
		uint8_t val = dumper->GetMemoryValue(memType, addr + i);
		ss << hex << uppercase << setw(2) << setfill('0') << (int)val;
	}
	ss << "\"";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleWriteBlock(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto addrIt = cmd.params.find("addr");
	auto hexIt = cmd.params.find("hex");

	if (addrIt == cmd.params.end() || hexIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing addr or hex parameter";
		return resp;
	}

	uint32_t addr = 0;
	string addrStr = addrIt->second;
	if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
		addr = std::stoul(addrStr.substr(2), nullptr, 16);
	} else {
		addr = std::stoul(addrStr, nullptr, 16);
	}

	string hexStr = hexIt->second;
	string cleaned;
	cleaned.reserve(hexStr.size());
	for (char c : hexStr) {
		if (std::isspace(static_cast<unsigned char>(c)) || c == ',') {
			continue;
		}
		cleaned.push_back(c);
	}

	if (cleaned.empty()) {
		resp.success = false;
		resp.error = "Empty hex payload";
		return resp;
	}
	if (cleaned.size() % 2 != 0) {
		resp.success = false;
		resp.error = "Hex payload must have an even length";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	MemoryType memType = MemoryType::SnesMemory;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
	}

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(memType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}

	uint32_t byteCount = static_cast<uint32_t>(cleaned.size() / 2);
	if(addr >= memSize || addr + byteCount > memSize) {
		resp.success = false;
		resp.error = "Address out of range";
		return resp;
	}

	for(uint32_t i = 0; i < byteCount; i++) {
		string byteStr = cleaned.substr(i * 2, 2);
		uint8_t value = static_cast<uint8_t>(std::stoul(byteStr, nullptr, 16));
		dumper->SetMemoryValue(memType, addr + i, value, false);
	}

	stringstream ss;
	ss << "{\"written\":" << byteCount << "}";
	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleSaveState(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto slotIt = cmd.params.find("slot");
	auto pathIt = cmd.params.find("path");

	if (slotIt != cmd.params.end()) {
		int slot = std::stoi(slotIt->second);
		emu->GetSaveStateManager()->SaveState(slot);
		resp.success = true;
		resp.data = "\"OK\"";
	} else if (pathIt != cmd.params.end()) {
		// Save to file path
		emu->GetSaveStateManager()->SaveState(pathIt->second);
		resp.success = true;
		resp.data = "\"OK\"";
	} else {
		resp.success = false;
		resp.error = "Missing slot or path parameter";
	}

	return resp;
}

SocketResponse SocketServer::HandleLoadState(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto slotIt = cmd.params.find("slot");
	auto pathIt = cmd.params.find("path");

	if (slotIt != cmd.params.end()) {
		int slot = std::stoi(slotIt->second);
		bool success = emu->GetSaveStateManager()->LoadState(slot);
		resp.success = success;
		resp.data = success ? "\"OK\"" : "";
		if (!success) resp.error = "Failed to load state from slot";
	} else if (pathIt != cmd.params.end()) {
		bool success = emu->GetSaveStateManager()->LoadState(pathIt->second);
		resp.success = success;
		resp.data = success ? "\"OK\"" : "";
		if (!success) resp.error = "Failed to load state from file";
	} else {
		resp.success = false;
		resp.error = "Missing slot or path parameter";
	}

	return resp;
}

SocketResponse SocketServer::HandleLoadScript(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto nameIt = cmd.params.find("name");
	auto pathIt = cmd.params.find("path");
	auto contentIt = cmd.params.find("content");

	string name = nameIt != cmd.params.end() ? nameIt->second : "cli_script";
	string path = pathIt != cmd.params.end() ? pathIt->second : "";
	string content = contentIt != cmd.params.end() ? contentIt->second : "";

	if (content.empty() && path.empty()) {
		resp.success = false;
		resp.error = "Must provide path or content";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	int32_t scriptId = dbg.GetDebugger()->GetScriptManager()->LoadScript(name, path, content, -1);

	resp.success = scriptId >= 0;
	if (resp.success) {
		resp.data = std::to_string(scriptId);
	} else {
		resp.error = "Failed to load script";
	}

	return resp;
}

SocketResponse SocketServer::HandleScreenshot(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	// Capture screenshot to PNG stream
	std::stringstream pngStream;
	emu->GetVideoDecoder()->TakeScreenshot(pngStream);

	string pngData = pngStream.str();
	if (pngData.empty()) {
		resp.success = false;
		resp.error = "Failed to capture screenshot";
		return resp;
	}

	// Base64 encode the PNG data
	static const char* base64Chars =
		"ABCDEFGHIJKLMNOPQRSTUVWXYZ"
		"abcdefghijklmnopqrstuvwxyz"
		"0123456789+/";

	string encoded;
	encoded.reserve(((pngData.size() + 2) / 3) * 4);

	uint32_t i = 0;
	uint8_t a3[3];
	uint8_t a4[4];

	for (char c : pngData) {
		a3[i++] = (uint8_t)c;
		if (i == 3) {
			a4[0] = (a3[0] & 0xfc) >> 2;
			a4[1] = ((a3[0] & 0x03) << 4) + ((a3[1] & 0xf0) >> 4);
			a4[2] = ((a3[1] & 0x0f) << 2) + ((a3[2] & 0xc0) >> 6);
			a4[3] = a3[2] & 0x3f;
			for (int j = 0; j < 4; j++) {
				encoded += base64Chars[a4[j]];
			}
			i = 0;
		}
	}

	if (i > 0) {
		for (uint32_t j = i; j < 3; j++) {
			a3[j] = 0;
		}
		a4[0] = (a3[0] & 0xfc) >> 2;
		a4[1] = ((a3[0] & 0x03) << 4) + ((a3[1] & 0xf0) >> 4);
		a4[2] = ((a3[1] & 0x0f) << 2) + ((a3[2] & 0xc0) >> 6);
		for (uint32_t j = 0; j < i + 1; j++) {
			encoded += base64Chars[a4[j]];
		}
		while (i++ < 3) {
			encoded += '=';
		}
	}

	resp.success = true;
	resp.data = "\"" + encoded + "\"";
	return resp;
}

SocketResponse SocketServer::HandleGetCpuState(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	// Get the CPU debugger for the main CPU type
	CpuType cpuType = emu->GetCpuTypes()[0];
	Debugger* debugger = dbg.GetDebugger();

	// Get program counter and flags using Debugger's direct methods
	uint32_t pc = debugger->GetProgramCounter(cpuType, true);
	uint8_t flags = debugger->GetCpuFlags(cpuType);

	stringstream ss;
	ss << "{";
	ss << "\"pc\":\"0x" << hex << uppercase << setw(6) << setfill('0') << pc << "\",";
	ss << "\"flags\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)flags << "\",";

	// Get more detailed state for SNES
	if (emu->GetConsoleType() == ConsoleType::Snes) {
		SnesCpuState& state = static_cast<SnesCpuState&>(debugger->GetCpuStateRef(cpuType));
		uint64_t cycleCount = state.CycleCount;
		uint16_t a = state.A;
		uint16_t x = state.X;
		uint16_t y = state.Y;
		uint16_t sp = state.SP;
		uint16_t d = state.D;
		uint8_t k = state.K;
		uint8_t dbr = state.DBR;
		uint8_t ps = state.PS;

		ss << "\"a\":\"0x" << hex << uppercase << setw(4) << setfill('0') << a << "\",";
		ss << "\"x\":\"0x" << hex << uppercase << setw(4) << setfill('0') << x << "\",";
		ss << "\"y\":\"0x" << hex << uppercase << setw(4) << setfill('0') << y << "\",";
		ss << "\"sp\":\"0x" << hex << uppercase << setw(4) << setfill('0') << sp << "\",";
		ss << "\"d\":\"0x" << hex << uppercase << setw(4) << setfill('0') << d << "\",";
		ss << "\"k\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)k << "\",";
		ss << "\"dbr\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)dbr << "\",";
		ss << "\"p\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)ps << "\",";
		ss << "\"cycles\":" << std::dec << cycleCount << ",";
	}

	ss << "\"consoleType\":" << static_cast<int>(emu->GetConsoleType());
	ss << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleStateInspector(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	bool running = emu->IsRunning();

	stringstream ss;
	ss << "{";
	ss << "\"running\":" << (running ? "true" : "false") << ",";
	ss << "\"consoleType\":" << static_cast<int>(emu->GetConsoleType());

	if(!running) {
		ss << "}";
		resp.success = true;
		resp.data = ss.str();
		return resp;
	}

	auto cpuTypes = emu->GetCpuTypes();
	CpuType cpuType = cpuTypes.empty() ? CpuType::Snes : cpuTypes[0];

	TimingInfo timing = emu->GetTimingInfo(cpuType);
	RomInfo& romInfo = emu->GetRomInfo();
	string romName = romInfo.RomFile.GetFileName();

	ss << ",\"romName\":\"" << JsonEscape(romName) << "\"";
	ss << ",\"system\":{";
	ss << "\"frameCount\":" << timing.FrameCount << ",";
	ss << "\"masterClock\":" << timing.MasterClock << ",";
	ss << "\"masterClockRate\":" << timing.MasterClockRate << ",";
	ss << "\"cycleCount\":" << timing.CycleCount;
	ss << "}";
	ss << ",\"mainCpuType\":" << static_cast<int>(cpuType);
	ss << ",\"mainCpuName\":\"" << CpuTypeName(cpuType) << "\"";

	auto dbg = emu->GetDebugger(true);
	if(dbg.GetDebugger()) {
		Debugger* debugger = dbg.GetDebugger();
		ss << ",\"debugger\":true";
		ss << ",\"cpus\":[";
		for(size_t i = 0; i < cpuTypes.size(); i++) {
			CpuType entryType = cpuTypes[i];
			if(i > 0) {
				ss << ",";
			}
			ss << "{";
			ss << "\"type\":" << static_cast<int>(entryType) << ",";
			ss << "\"name\":\"" << CpuTypeName(entryType) << "\",";
			ss << "\"state\":{";
			AppendCpuStateJson(ss, entryType, debugger);
			ss << "}";
			ss << "}";
		}
		ss << "]";

		if(emu->GetConsoleType() == ConsoleType::Snes) {
			SnesCpuState& cpu = static_cast<SnesCpuState&>(debugger->GetCpuStateRef(cpuType));

			ss << ",\"cpu\":{";
			ss << "\"type\":" << static_cast<int>(cpuType) << ",";
			ss << "\"a\":\"" << FormatHex(cpu.A, 4) << "\",";
			ss << "\"x\":\"" << FormatHex(cpu.X, 4) << "\",";
			ss << "\"y\":\"" << FormatHex(cpu.Y, 4) << "\",";
			ss << "\"sp\":\"" << FormatHex(cpu.SP, 4) << "\",";
			ss << "\"d\":\"" << FormatHex(cpu.D, 4) << "\",";
			ss << "\"pc\":\"" << FormatHex(((uint32_t)cpu.K << 16) | cpu.PC, 6) << "\",";
			ss << "\"k\":\"" << FormatHex(cpu.K, 2) << "\",";
			ss << "\"dbr\":\"" << FormatHex(cpu.DBR, 2) << "\",";
			ss << "\"p\":\"" << FormatHex(cpu.PS, 2) << "\",";
			ss << "\"flags\":\"" << FormatSnesFlags(cpu) << "\",";
			ss << "\"emulation\":" << (cpu.EmulationMode ? "true" : "false");
			ss << "}";

			SnesPpuState ppu;
			debugger->GetPpuState(ppu, CpuType::Snes);

			ss << ",\"ppu\":{";
			ss << "\"scanline\":" << ppu.Scanline << ",";
			ss << "\"cycle\":" << ppu.Cycle << ",";
			ss << "\"frame\":" << ppu.FrameCount << ",";
			ss << "\"forcedBlank\":" << (ppu.ForcedBlank ? "true" : "false") << ",";
			ss << "\"brightness\":" << static_cast<int>(ppu.ScreenBrightness);
			ss << "}";
		}
	} else {
		ss << ",\"debugger\":false";
		ss << ",\"cpus\":[]";
	}

	string watchHudText;
	string watchHudData;
	if(emu->GetVideoRenderer()) {
		watchHudText = emu->GetVideoRenderer()->GetWatchHudText();
		watchHudData = emu->GetVideoRenderer()->GetWatchHudData();
	}
	ss << ",\"watchHudText\":\"" << JsonEscape(watchHudText) << "\"";
	ss << ",\"watchEntries\":" << (watchHudData.empty() ? "{}" : watchHudData);

	ss << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleSetInput(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto buttonsIt = cmd.params.find("buttons");
	if (buttonsIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing buttons parameter";
		return resp;
	}

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	// Parse button string - format: "A,B,UP,DOWN" or just "A" for single button
	string buttons = buttonsIt->second;
	DebugControllerState state = {};

	// Parse comma-separated button names
	size_t pos = 0;
	string token;
	while (pos < buttons.size()) {
		size_t nextComma = buttons.find(',', pos);
		if (nextComma == string::npos) {
			token = buttons.substr(pos);
			pos = buttons.size();
		} else {
			token = buttons.substr(pos, nextComma - pos);
			pos = nextComma + 1;
		}

		// Trim whitespace
		while (!token.empty() && token.front() == ' ') token.erase(0, 1);
		while (!token.empty() && token.back() == ' ') token.pop_back();

		// Convert to uppercase for matching
		for (char& c : token) c = toupper(c);

		// Set the button state
		if (token == "A") state.A = true;
		else if (token == "B") state.B = true;
		else if (token == "X") state.X = true;
		else if (token == "Y") state.Y = true;
		else if (token == "L") state.L = true;
		else if (token == "R") state.R = true;
		else if (token == "UP") state.Up = true;
		else if (token == "DOWN") state.Down = true;
		else if (token == "LEFT") state.Left = true;
		else if (token == "RIGHT") state.Right = true;
		else if (token == "SELECT") state.Select = true;
		else if (token == "START") state.Start = true;
	}

	// Get player index (default to player 1 / index 0)
	uint32_t playerIndex = 0;
	auto indexIt = cmd.params.find("player");
	if (indexIt != cmd.params.end()) {
		playerIndex = std::stoul(indexIt->second);
		if (playerIndex > 7) playerIndex = 0;
	}

	// Get frame count (default 0 = indefinite for backward compatibility)
	uint32_t frameCount = 0;
	auto framesIt = cmd.params.find("frames");
	if (framesIt != cmd.params.end()) {
		frameCount = std::stoul(framesIt->second);
	}

	// Set the input override via debugger
	dbg.GetDebugger()->SetInputOverrides(playerIndex, state, frameCount);

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleDisasm(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto addrIt = cmd.params.find("addr");
	if (addrIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing addr parameter";
		return resp;
	}

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	uint32_t addr = 0;
	string addrStr = addrIt->second;
	if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
		addr = std::stoul(addrStr.substr(2), nullptr, 16);
	} else {
		addr = std::stoul(addrStr, nullptr, 16);
	}

	// Get line count (default 10)
	uint32_t count = 10;
	auto countIt = cmd.params.find("count");
	if (countIt != cmd.params.end()) {
		count = std::stoul(countIt->second);
		if (count > 100) count = 100;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	CpuType cpuType = emu->GetCpuTypes()[0];
	MemoryType cpuMemType = DebugUtilities::GetCpuMemoryType(cpuType);

	MemoryType memType = cpuMemType;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
		if(memType != cpuMemType) {
			resp.success = false;
			resp.error = "DISASM only supports CPU memory";
			return resp;
		}
	}

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(cpuMemType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}
	if(addr >= memSize) {
		resp.success = false;
		resp.error = "Address out of range";
		return resp;
	}

	vector<CodeLineData> lines(count);
	uint32_t lineCount = dbg.GetDebugger()->GetDisassembler()->GetDisassemblyOutput(cpuType, addr, lines.data(), count);
	if(lineCount == 0) {
		resp.success = false;
		resp.error = "Address out of range";
		return resp;
	}

	int pcSize = DebugUtilities::GetProgramCounterSize(cpuType);
	stringstream ss;
	ss << "[";
	for (uint32_t i = 0; i < lineCount; i++) {
		if (i > 0) ss << ",";
		CodeLineData& line = lines[i];

		ss << "{";
		if(line.Address >= 0) {
			ss << "\"addr\":\"0x" << hex << uppercase << setw(pcSize) << setfill('0') << (uint32_t)line.Address << "\"";
		} else {
			ss << "\"addr\":null";
		}
		ss << ",\"text\":\"" << JsonEscape(line.Text) << "\"";
		if(line.Comment[0] != '\0') {
			ss << ",\"comment\":\"" << JsonEscape(line.Comment) << "\"";
		}
		ss << ",\"bytes\":\"";
		for(uint8_t b = 0; b < line.OpSize && b < sizeof(line.ByteCode); b++) {
			ss << hex << uppercase << setw(2) << setfill('0') << (int)line.ByteCode[b];
		}
		ss << "\"";
		ss << ",\"opSize\":" << dec << (int)line.OpSize;
		ss << "}";
	}
	ss << "]";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleStep(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	// Get step count (default 1)
	int32_t stepCount = 1;
	auto countIt = cmd.params.find("count");
	if (countIt != cmd.params.end()) {
		stepCount = std::stoi(countIt->second);
	}

	// Get step mode (default: into)
	string mode = "into";
	auto modeIt = cmd.params.find("mode");
	if (modeIt != cmd.params.end()) {
		mode = modeIt->second;
	}

	CpuType cpuType = emu->GetCpuTypes()[0];
	Debugger* debugger = dbg.GetDebugger();

	StepType stepType = StepType::Step;
	if (mode == "over") {
		stepType = StepType::StepOver;
	} else if (mode == "out") {
		stepType = StepType::StepOut;
	}

	debugger->Step(cpuType, stepCount, stepType);

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleRunFrame(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	// Get frame count (default 1)
	int32_t frameCount = 1;
	auto countIt = cmd.params.find("count");
	if (countIt != cmd.params.end()) {
		frameCount = std::stoi(countIt->second);
		if (frameCount > 600) frameCount = 600; // Max 10 seconds at 60fps
	}

	auto dbg = emu->GetDebugger(true);
	Debugger* debugger = dbg.GetDebugger();
	if (debugger) {
		CpuType cpuType = emu->GetCpuTypes()[0];
		// Step by frames
		debugger->Step(cpuType, frameCount, StepType::PpuStep);
	} else {
		if(frameCount != 1) {
			resp.success = false;
			resp.error = "Debugger required for multi-frame stepping";
			return resp;
		}
		emu->PauseOnNextFrame();
		if(emu->IsPaused()) {
			emu->Resume();
		}
	}

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

// ============================================================================
// Emulation Control Handlers
// ============================================================================

SocketResponse SocketServer::HandleRomInfo(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	RomInfo& romInfo = emu->GetRomInfo();

	stringstream ss;
	ss << "{";

	// Get filename from VirtualFile
	string filename = romInfo.RomFile.GetFileName();
	ss << "\"filename\":\"" << JsonEscape(filename) << "\",";

	// ROM format
	string formatName;
	switch (romInfo.Format) {
		case RomFormat::Sfc: formatName = "Sfc"; break;
		case RomFormat::Spc: formatName = "Spc"; break;
		case RomFormat::Gb: formatName = "Gb"; break;
		case RomFormat::Gbs: formatName = "Gbs"; break;
		case RomFormat::iNes: formatName = "iNes"; break;
		case RomFormat::Unif: formatName = "Unif"; break;
		case RomFormat::Fds: formatName = "Fds"; break;
		case RomFormat::Nsf: formatName = "Nsf"; break;
		case RomFormat::Pce: formatName = "Pce"; break;
		case RomFormat::PceCdRom: formatName = "PceCdRom"; break;
		case RomFormat::PceHes: formatName = "PceHes"; break;
		case RomFormat::Sms: formatName = "Sms"; break;
		case RomFormat::GameGear: formatName = "GameGear"; break;
		case RomFormat::Sg: formatName = "Sg"; break;
		case RomFormat::Gba: formatName = "Gba"; break;
		default: formatName = "Unknown"; break;
	}
	ss << "\"format\":\"" << formatName << "\",";

	// Console type
	ss << "\"consoleType\":" << static_cast<int>(emu->GetConsoleType()) << ",";

	// CRC32
	ss << "\"crc32\":\"" << hex << uppercase << setw(8) << setfill('0') << emu->GetCrc32() << "\",";

	// SHA1
	ss << "\"sha1\":\"" << emu->GetHash(HashType::Sha1) << "\",";

	// Frame count
	ss << "\"frameCount\":" << std::dec << emu->GetFrameCount();

	ss << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleRewind(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	RewindManager* rewindMgr = emu->GetRewindManager();
	if (!rewindMgr) {
		resp.success = false;
		resp.error = "Rewind manager not available";
		return resp;
	}

	if (!rewindMgr->HasHistory()) {
		resp.success = false;
		resp.error = "No rewind history available";
		return resp;
	}

	// Get seconds to rewind (default 1)
	uint32_t seconds = 1;
	auto secondsIt = cmd.params.find("seconds");
	if (secondsIt != cmd.params.end()) {
		seconds = std::stoul(secondsIt->second);
		if (seconds > 300) seconds = 300; // Max 5 minutes
	}

	rewindMgr->RewindSeconds(seconds);

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleCheat(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	CheatManager* cheatMgr = emu->GetCheatManager();
	if (!cheatMgr) {
		resp.success = false;
		resp.error = "Cheat manager not available";
		return resp;
	}

	// Get action (add, list, clear)
	string action = "list";
	auto actionIt = cmd.params.find("action");
	if (actionIt != cmd.params.end()) {
		action = actionIt->second;
	}

	if (action == "list") {
		// List current cheats
		vector<CheatCode> cheats = cheatMgr->GetCheats();

		stringstream ss;
		ss << "{\"cheats\":[";

		bool first = true;
		for (const auto& cheat : cheats) {
			if (!first) ss << ",";
			first = false;

			ss << "{\"code\":\"" << JsonEscape(cheat.Code) << "\",";
			ss << "\"type\":" << static_cast<int>(cheat.Type) << "}";
		}

		ss << "],\"count\":" << cheats.size() << "}";

		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "add") {
		// Add a cheat code
		auto codeIt = cmd.params.find("code");
		if (codeIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing code parameter";
			return resp;
		}

		// Determine cheat type based on format param or console type
		CheatType type = CheatType::SnesProActionReplay; // Default for SNES

		auto formatIt = cmd.params.find("format");
		if (formatIt != cmd.params.end()) {
			string fmt = formatIt->second;
			if (fmt == "GameGenie" || fmt == "gamegenie") {
				if (emu->GetConsoleType() == ConsoleType::Snes) {
					type = CheatType::SnesGameGenie;
				} else if (emu->GetConsoleType() == ConsoleType::Nes) {
					type = CheatType::NesGameGenie;
				} else if (emu->GetConsoleType() == ConsoleType::Gameboy) {
					type = CheatType::GbGameGenie;
				}
			} else if (fmt == "ProActionReplay" || fmt == "par") {
				if (emu->GetConsoleType() == ConsoleType::Snes) {
					type = CheatType::SnesProActionReplay;
				} else if (emu->GetConsoleType() == ConsoleType::Nes) {
					type = CheatType::NesProActionRocky;
				}
			} else if (fmt == "GameShark" || fmt == "gameshark") {
				type = CheatType::GbGameShark;
			}
		}

		CheatCode cheat;
		cheat.Type = type;
		strncpy(cheat.Code, codeIt->second.c_str(), sizeof(cheat.Code) - 1);
		cheat.Code[sizeof(cheat.Code) - 1] = '\0';

		bool success = cheatMgr->AddCheat(cheat);

		resp.success = success;
		if (success) {
			resp.data = "\"OK\"";
		} else {
			resp.error = "Failed to add cheat code";
		}
	}
	else if (action == "clear") {
		cheatMgr->ClearCheats();
		resp.success = true;
		resp.data = "\"OK\"";
	}
	else {
		resp.success = false;
		resp.error = "Unknown action: " + action;
	}

	return resp;
}

SocketResponse SocketServer::HandleSpeed(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto multiplierIt = cmd.params.find("multiplier");
	if (multiplierIt == cmd.params.end()) {
		// No multiplier provided - return current speed info
		stringstream ss;
		ss << "{\"fps\":" << fixed << setprecision(2) << emu->GetFps() << "}";
		resp.success = true;
		resp.data = ss.str();
		return resp;
	}

	// Parse multiplier (0 = max speed, 1 = normal, 2 = 2x, etc.)
	double multiplier = std::stod(multiplierIt->second);
	if (multiplier < 0) {
		resp.success = false;
		resp.error = "Multiplier must be >= 0";
		return resp;
	}

	EmuSettings* settings = emu->GetSettings();
	if (!settings) {
		resp.success = false;
		resp.error = "Settings not available";
		return resp;
	}

	// Get current emulation config and modify speed
	EmulationConfig config = settings->GetEmulationConfig();

	if (multiplier == 0) {
		// Max speed - set a very high value
		config.EmulationSpeed = 0; // 0 typically means unlimited in Mesen
	} else {
		// Normal speed multiplier (100 = 1x, 200 = 2x, etc.)
		config.EmulationSpeed = static_cast<uint32_t>(multiplier * 100);
	}

	settings->SetEmulationConfig(config);

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

// ============================================================================
// Memory Analysis Handlers
// ============================================================================

static string NormalizeKey(string value)
{
	string out;
	out.reserve(value.size());
	for(unsigned char c : value) {
		if(std::isalnum(c)) {
			out.push_back((char)std::tolower(c));
		}
	}
	return out;
}

static string FormatHex(uint64_t value, int width)
{
	stringstream ss;
	ss << "0x" << hex << uppercase << setw(width) << setfill('0') << value;
	return ss.str();
}

static string FormatSnesFlags(const SnesCpuState& cpu)
{
	bool flagN = (cpu.PS & ProcFlags::Negative) != 0;
	bool flagV = (cpu.PS & ProcFlags::Overflow) != 0;
	bool flagM = (cpu.PS & ProcFlags::MemoryMode8) != 0;
	bool flagX = (cpu.PS & ProcFlags::IndexMode8) != 0;
	bool flagD = (cpu.PS & ProcFlags::Decimal) != 0;
	bool flagI = (cpu.PS & ProcFlags::IrqDisable) != 0;
	bool flagZ = (cpu.PS & ProcFlags::Zero) != 0;
	bool flagC = (cpu.PS & ProcFlags::Carry) != 0;
	char flagE = cpu.EmulationMode ? 'E' : 'e';

	stringstream ss;
	ss << (flagN ? 'N' : 'n')
	   << (flagV ? 'V' : 'v')
	   << (flagM ? 'M' : 'm')
	   << (flagX ? 'X' : 'x')
	   << (flagD ? 'D' : 'd')
	   << (flagI ? 'I' : 'i')
	   << (flagZ ? 'Z' : 'z')
	   << (flagC ? 'C' : 'c')
	   << ' ' << flagE;
	return ss.str();
}

static string CpuTypeName(CpuType cpuType)
{
	switch(cpuType) {
		case CpuType::Snes: return "snes";
		case CpuType::Spc: return "spc";
		case CpuType::NecDsp: return "necdsp";
		case CpuType::Sa1: return "sa1";
		case CpuType::Gsu: return "gsu";
		case CpuType::Cx4: return "cx4";
		case CpuType::Gameboy: return "gameboy";
		case CpuType::Nes: return "nes";
		case CpuType::Pce: return "pce";
		case CpuType::Sms: return "sms";
		case CpuType::Gba: return "gba";
	}
	return "unknown";
}

static void AppendCpuStateJson(stringstream& ss, CpuType cpuType, Debugger* debugger)
{
	bool first = true;
	auto addField = [&](const string& key, const string& value, bool raw = false) {
		if(!first) {
			ss << ",";
		}
		first = false;
		ss << "\"" << key << "\":";
		if(raw) {
			ss << value;
		} else {
			ss << "\"" << value << "\"";
		}
	};

	switch(cpuType) {
		case CpuType::Snes:
		case CpuType::Sa1: {
			SnesCpuState& cpu = static_cast<SnesCpuState&>(debugger->GetCpuStateRef(cpuType));
			uint32_t pc = ((uint32_t)cpu.K << 16) | cpu.PC;
			addField("a", FormatHex(cpu.A, 4));
			addField("x", FormatHex(cpu.X, 4));
			addField("y", FormatHex(cpu.Y, 4));
			addField("sp", FormatHex(cpu.SP, 4));
			addField("d", FormatHex(cpu.D, 4));
			addField("pc", FormatHex(pc, 6));
			addField("k", FormatHex(cpu.K, 2));
			addField("dbr", FormatHex(cpu.DBR, 2));
			addField("p", FormatHex(cpu.PS, 2));
			addField("flags", FormatSnesFlags(cpu));
			addField("emulation", cpu.EmulationMode ? "true" : "false", true);
			addField("cycleCount", std::to_string(cpu.CycleCount), true);
			break;
		}

		case CpuType::Spc: {
			SpcState& spc = static_cast<SpcState&>(debugger->GetCpuStateRef(cpuType));
			addField("pc", FormatHex(spc.PC, 4));
			addField("a", FormatHex(spc.A, 2));
			addField("x", FormatHex(spc.X, 2));
			addField("y", FormatHex(spc.Y, 2));
			addField("sp", FormatHex(spc.SP, 2));
			addField("p", FormatHex(spc.PS, 2));
			addField("cycleCount", std::to_string(spc.Cycle), true);
			break;
		}

		case CpuType::NecDsp: {
			NecDspState& dsp = static_cast<NecDspState&>(debugger->GetCpuStateRef(cpuType));
			addField("pc", FormatHex(dsp.PC, 4));
			addField("a", FormatHex(dsp.A, 4));
			addField("b", FormatHex(dsp.B, 4));
			addField("sr", FormatHex(dsp.SR, 4));
			addField("cycleCount", std::to_string(dsp.CycleCount), true);
			break;
		}

		case CpuType::Gsu: {
			GsuState& gsu = static_cast<GsuState&>(debugger->GetCpuStateRef(cpuType));
			uint32_t pc = ((uint32_t)gsu.ProgramBank << 16) | gsu.R[15];
			addField("pc", FormatHex(pc, 6));
			addField("sfrLow", FormatHex(gsu.SFR.GetFlagsLow(), 2));
			addField("sfrHigh", FormatHex(gsu.SFR.GetFlagsHigh(), 2));
			addField("cycleCount", std::to_string(gsu.CycleCount), true);
			ss << (first ? "" : ",") << "\"r\":[";
			for(int i = 0; i < 16; i++) {
				if(i > 0) {
					ss << ",";
				}
				ss << "\"" << FormatHex(gsu.R[i], 4) << "\"";
			}
			ss << "]";
			first = false;
			break;
		}

		case CpuType::Cx4: {
			Cx4State& cx4 = static_cast<Cx4State&>(debugger->GetCpuStateRef(cpuType));
			uint32_t pc = ((uint32_t)cx4.PB << 16) | cx4.PC;
			addField("pc", FormatHex(pc, 6));
			addField("a", FormatHex(cx4.A, 8));
			addField("p", FormatHex(cx4.P, 4));
			addField("sp", FormatHex(cx4.SP, 2));
			string flags;
			flags.push_back(cx4.Negative ? 'N' : 'n');
			flags.push_back(cx4.Overflow ? 'V' : 'v');
			flags.push_back(cx4.Zero ? 'Z' : 'z');
			flags.push_back(cx4.Carry ? 'C' : 'c');
			flags.push_back(cx4.IrqFlag ? 'I' : 'i');
			addField("flags", flags);
			addField("cycleCount", std::to_string(cx4.CycleCount), true);
			break;
		}

		default: {
			uint32_t pc = debugger->GetProgramCounter(cpuType, true);
			uint8_t flags = debugger->GetCpuFlags(cpuType);
			addField("pc", FormatHex(pc, DebugUtilities::GetProgramCounterSize(cpuType)));
			addField("flags", FormatHex(flags, 2));
			break;
		}
	}
}

// Helper to parse memory type from string
static bool TryParseMemoryType(const string& memtype, MemoryType& outType)
{
	string key = NormalizeKey(memtype);
	if (key.empty() || key == "snesmemory" || key == "snes") { outType = MemoryType::SnesMemory; return true; }
	if (key == "snesworkram" || key == "wram") { outType = MemoryType::SnesWorkRam; return true; }
	if (key == "snessaveram" || key == "sram") { outType = MemoryType::SnesSaveRam; return true; }
	if (key == "snesprgrom" || key == "rom") { outType = MemoryType::SnesPrgRom; return true; }
	if (key == "snesvideoram" || key == "vram") { outType = MemoryType::SnesVideoRam; return true; }
	if (key == "snesspriteram" || key == "oam") { outType = MemoryType::SnesSpriteRam; return true; }
	if (key == "snescgram" || key == "cgram") { outType = MemoryType::SnesCgRam; return true; }
	if (key == "snesregister" || key == "register") { outType = MemoryType::SnesRegister; return true; }
	if (key == "snesmemorymap") { outType = MemoryType::SnesMemory; return true; }
	if (key == "bsxpsram") { outType = MemoryType::BsxPsRam; return true; }
	if (key == "bsxmemorypack") { outType = MemoryType::BsxMemoryPack; return true; }

	if (key == "spcmemory" || key == "spc") { outType = MemoryType::SpcMemory; return true; }
	if (key == "spcram") { outType = MemoryType::SpcRam; return true; }
	if (key == "spcrom") { outType = MemoryType::SpcRom; return true; }
	if (key == "spcdspregisters" || key == "spcdspregs" || key == "spcdsp") { outType = MemoryType::SpcDspRegisters; return true; }
	if (key == "dspprgrom" || key == "dspprogramrom") { outType = MemoryType::DspProgramRom; return true; }
	if (key == "dspdatarom") { outType = MemoryType::DspDataRom; return true; }
	if (key == "dspdataram" || key == "dspdatram") { outType = MemoryType::DspDataRam; return true; }
	if (key == "necdsp" || key == "necdspmemory" || key == "dspmemory") { outType = MemoryType::NecDspMemory; return true; }

	if (key == "sa1" || key == "sa1memory") { outType = MemoryType::Sa1Memory; return true; }
	if (key == "sa1internalram" || key == "sa1ram") { outType = MemoryType::Sa1InternalRam; return true; }

	if (key == "gsu" || key == "gsumemory") { outType = MemoryType::GsuMemory; return true; }
	if (key == "gsuworkram" || key == "gsuram") { outType = MemoryType::GsuWorkRam; return true; }

	if (key == "cx4" || key == "cx4memory") { outType = MemoryType::Cx4Memory; return true; }
	if (key == "cx4dataram" || key == "cx4ram") { outType = MemoryType::Cx4DataRam; return true; }

	if (key == "gb" || key == "gameboy" || key == "gbmemory") { outType = MemoryType::GameboyMemory; return true; }
	if (key == "gbprgrom") { outType = MemoryType::GbPrgRom; return true; }
	if (key == "gbworkram" || key == "gbwram") { outType = MemoryType::GbWorkRam; return true; }
	if (key == "gbcartram" || key == "gbcart") { outType = MemoryType::GbCartRam; return true; }
	if (key == "gbhighram" || key == "gbhram") { outType = MemoryType::GbHighRam; return true; }
	if (key == "gbbootrom") { outType = MemoryType::GbBootRom; return true; }
	if (key == "gbvideoram" || key == "gbvram") { outType = MemoryType::GbVideoRam; return true; }
	if (key == "gbspriteram" || key == "gboam") { outType = MemoryType::GbSpriteRam; return true; }

	if (key == "nes" || key == "nesmemory") { outType = MemoryType::NesMemory; return true; }
	if (key == "nesppumemory") { outType = MemoryType::NesPpuMemory; return true; }
	if (key == "nesprgrom") { outType = MemoryType::NesPrgRom; return true; }
	if (key == "nesinternalram") { outType = MemoryType::NesInternalRam; return true; }
	if (key == "nesworkram" || key == "neswram") { outType = MemoryType::NesWorkRam; return true; }
	if (key == "nessaveram") { outType = MemoryType::NesSaveRam; return true; }
	if (key == "nesnametableram" || key == "nesnametable") { outType = MemoryType::NesNametableRam; return true; }
	if (key == "nesspriteram" || key == "nesoam") { outType = MemoryType::NesSpriteRam; return true; }
	if (key == "nessecondaryspriteram") { outType = MemoryType::NesSecondarySpriteRam; return true; }
	if (key == "nespaletteram" || key == "nespalette") { outType = MemoryType::NesPaletteRam; return true; }
	if (key == "neschrram") { outType = MemoryType::NesChrRam; return true; }
	if (key == "neschrrom") { outType = MemoryType::NesChrRom; return true; }

	if (key == "pce" || key == "pcengine" || key == "pcememory") { outType = MemoryType::PceMemory; return true; }
	if (key == "pceprgrom") { outType = MemoryType::PcePrgRom; return true; }
	if (key == "pceworkram" || key == "pcewram") { outType = MemoryType::PceWorkRam; return true; }
	if (key == "pcesaveram") { outType = MemoryType::PceSaveRam; return true; }
	if (key == "pcecdromram") { outType = MemoryType::PceCdromRam; return true; }
	if (key == "pcecardram") { outType = MemoryType::PceCardRam; return true; }
	if (key == "pceadpcmram") { outType = MemoryType::PceAdpcmRam; return true; }
	if (key == "pcearcadecardram") { outType = MemoryType::PceArcadeCardRam; return true; }
	if (key == "pcevideoram") { outType = MemoryType::PceVideoRam; return true; }
	if (key == "pcevideoramvdc2") { outType = MemoryType::PceVideoRamVdc2; return true; }
	if (key == "pcespriteram") { outType = MemoryType::PceSpriteRam; return true; }
	if (key == "pcespriteramvdc2") { outType = MemoryType::PceSpriteRamVdc2; return true; }
	if (key == "pcepaletteram") { outType = MemoryType::PcePaletteRam; return true; }

	if (key == "sms" || key == "smsmemory") { outType = MemoryType::SmsMemory; return true; }
	if (key == "smsprgrom") { outType = MemoryType::SmsPrgRom; return true; }
	if (key == "smsworkram" || key == "smswram") { outType = MemoryType::SmsWorkRam; return true; }
	if (key == "smscartram") { outType = MemoryType::SmsCartRam; return true; }
	if (key == "smsbootrom") { outType = MemoryType::SmsBootRom; return true; }
	if (key == "smsvideoram" || key == "smsvram") { outType = MemoryType::SmsVideoRam; return true; }
	if (key == "smspaletteram") { outType = MemoryType::SmsPaletteRam; return true; }
	if (key == "smsport") { outType = MemoryType::SmsPort; return true; }

	if (key == "gba" || key == "gbamemory") { outType = MemoryType::GbaMemory; return true; }
	if (key == "gbaprgrom") { outType = MemoryType::GbaPrgRom; return true; }
	if (key == "gbabootrom") { outType = MemoryType::GbaBootRom; return true; }
	if (key == "gbasaveram") { outType = MemoryType::GbaSaveRam; return true; }
	if (key == "gbainternalram" || key == "gbaintworkram" || key == "gbaintwram") { outType = MemoryType::GbaIntWorkRam; return true; }
	if (key == "gbaexternalram" || key == "gbaextworkram" || key == "gbaextwram") { outType = MemoryType::GbaExtWorkRam; return true; }
	if (key == "gbavideoram" || key == "gbavram") { outType = MemoryType::GbaVideoRam; return true; }
	if (key == "gbaspriteram" || key == "gbaoam") { outType = MemoryType::GbaSpriteRam; return true; }
	if (key == "gbapaletteram") { outType = MemoryType::GbaPaletteRam; return true; }
	return false;
}

static string JsonEscape(const string& value)
{
	string escaped;
	escaped.reserve(value.size());
	for(unsigned char c : value) {
		switch(c) {
			case '\\': escaped += "\\\\"; break;
			case '"': escaped += "\\\""; break;
			case '\b': escaped += "\\b"; break;
			case '\f': escaped += "\\f"; break;
			case '\n': escaped += "\\n"; break;
			case '\r': escaped += "\\r"; break;
			case '\t': escaped += "\\t"; break;
			default:
				if(c < 0x20) {
					char buf[7];
					snprintf(buf, sizeof(buf), "\\u%04X", c);
					escaped += buf;
				} else {
					escaped += static_cast<char>(c);
				}
				break;
		}
	}
	return escaped;
}

static string Trim(string value)
{
	auto isSpace = [](unsigned char ch) { return std::isspace(ch) != 0; };
	while(!value.empty() && isSpace(value.front())) {
		value.erase(value.begin());
	}
	while(!value.empty() && isSpace(value.back())) {
		value.pop_back();
	}
	return value;
}

static void AppendUtf8(string& out, uint32_t codepoint)
{
	if(codepoint <= 0x7F) {
		out.push_back(static_cast<char>(codepoint));
	} else if(codepoint <= 0x7FF) {
		out.push_back(static_cast<char>(0xC0 | ((codepoint >> 6) & 0x1F)));
		out.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
	} else if(codepoint <= 0xFFFF) {
		out.push_back(static_cast<char>(0xE0 | ((codepoint >> 12) & 0x0F)));
		out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
		out.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
	} else {
		out.push_back(static_cast<char>(0xF0 | ((codepoint >> 18) & 0x07)));
		out.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
		out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
		out.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
	}
}

static bool ParseJsonString(const string& json, size_t& index, string& out, string& error)
{
	if(index >= json.size() || json[index] != '"') {
		error = "Expected '\"' to start string";
		return false;
	}

	index++; // skip opening quote
	out.clear();
	while(index < json.size()) {
		char c = json[index++];
		if(c == '"') {
			return true;
		}
		if(c == '\\') {
			if(index >= json.size()) {
				error = "Unexpected end of escape sequence";
				return false;
			}
			char esc = json[index++];
			switch(esc) {
				case '"': out.push_back('"'); break;
				case '\\': out.push_back('\\'); break;
				case '/': out.push_back('/'); break;
				case 'b': out.push_back('\b'); break;
				case 'f': out.push_back('\f'); break;
				case 'n': out.push_back('\n'); break;
				case 'r': out.push_back('\r'); break;
				case 't': out.push_back('\t'); break;
				case 'u': {
					if(index + 3 >= json.size()) {
						error = "Invalid unicode escape";
						return false;
					}
					uint32_t codepoint = 0;
					for(int i = 0; i < 4; i++) {
						char h = json[index++];
						codepoint <<= 4;
						if(h >= '0' && h <= '9') codepoint |= (h - '0');
						else if(h >= 'a' && h <= 'f') codepoint |= (h - 'a' + 10);
						else if(h >= 'A' && h <= 'F') codepoint |= (h - 'A' + 10);
						else {
							error = "Invalid hex digit in unicode escape";
							return false;
						}
					}
					AppendUtf8(out, codepoint);
					break;
				}
				default:
					error = "Unsupported escape sequence";
					return false;
			}
		} else {
			out.push_back(c);
		}
	}

	error = "Unterminated string";
	return false;
}

static bool ParseJsonObject(const string& json, unordered_map<string, string>& out, string& error)
{
	out.clear();
	size_t index = 0;
	auto skipWhitespace = [&](void) {
		while(index < json.size() && std::isspace(static_cast<unsigned char>(json[index]))) {
			index++;
		}
	};

	skipWhitespace();
	if(index >= json.size() || json[index] != '{') {
		error = "Expected '{' at start of JSON object";
		return false;
	}
	index++;

	while(true) {
		skipWhitespace();
		if(index >= json.size()) {
			error = "Unexpected end of JSON object";
			return false;
		}
		if(json[index] == '}') {
			index++;
			break;
		}

		string key;
		if(!ParseJsonString(json, index, key, error)) {
			return false;
		}

		skipWhitespace();
		if(index >= json.size() || json[index] != ':') {
			error = "Expected ':' after key";
			return false;
		}
		index++;

		skipWhitespace();
		if(index >= json.size()) {
			error = "Unexpected end of JSON object";
			return false;
		}

		string value;
		char c = json[index];
		if(c == '"') {
			if(!ParseJsonString(json, index, value, error)) {
				return false;
			}
		} else if(c == '{' || c == '[') {
			error = "Nested JSON values are not supported";
			return false;
		} else {
			size_t start = index;
			while(index < json.size()) {
				char v = json[index];
				if(v == ',' || v == '}' || std::isspace(static_cast<unsigned char>(v))) {
					break;
				}
				index++;
			}
			value = Trim(json.substr(start, index - start));
		}

		out[key] = value;

		skipWhitespace();
		if(index >= json.size()) {
			error = "Unexpected end of JSON object";
			return false;
		}
		if(json[index] == ',') {
			index++;
			continue;
		}
		if(json[index] == '}') {
			index++;
			break;
		}

		error = "Expected ',' or '}' after value";
		return false;
	}

	skipWhitespace();
	if(index < json.size()) {
		string trailing = Trim(json.substr(index));
		if(!trailing.empty()) {
			error = "Unexpected trailing characters";
			return false;
		}
	}

	return true;
}

static bool ReadRequestLine(int clientFd, std::atomic<bool>& running, string& out, string& error)
{
	constexpr size_t kMaxRequestBytes = 1024 * 1024; // 1MB
	constexpr int kTotalTimeoutMs = 5000;
	constexpr int kPollSliceMs = 50;

	out.clear();
	error.clear();

	auto finalizeIfHasData = [&]() -> bool {
		while(!out.empty() && (out.back() == '\r' || out.back() == '\n')) {
			out.pop_back();
		}
		return !out.empty();
	};

	int flags = fcntl(clientFd, F_GETFL, 0);
	if(flags != -1) {
		fcntl(clientFd, F_SETFL, flags | O_NONBLOCK);
	}

	auto start = std::chrono::steady_clock::now();
	while(running.load()) {
		auto now = std::chrono::steady_clock::now();
		auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - start).count();
		if(elapsed >= kTotalTimeoutMs) {
			if(finalizeIfHasData()) {
				return true;
			}
			error = "Timeout waiting for request";
			return false;
		}

		int timeout = std::min<int>(kPollSliceMs, kTotalTimeoutMs - static_cast<int>(elapsed));
		struct pollfd pfd;
		pfd.fd = clientFd;
		pfd.events = POLLIN;
		pfd.revents = 0;

		int ret = poll(&pfd, 1, timeout);
		if(ret < 0) {
			if(errno == EINTR) {
				continue;
			}
			error = "Poll failed";
			return false;
		}
		if(ret == 0) {
			continue;
		}
		if(pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
			if(finalizeIfHasData()) {
				return true;
			}
			error = "Client disconnected";
			return false;
		}
		if(!(pfd.revents & POLLIN)) {
			continue;
		}

		char buffer[4096];
		ssize_t n = read(clientFd, buffer, sizeof(buffer));
		if(n < 0) {
			if(errno == EAGAIN || errno == EWOULDBLOCK) {
				continue;
			}
			error = "Failed to read request";
			return false;
		}
		if(n == 0) {
			if(finalizeIfHasData()) {
				return true;
			}
			error = "Client closed connection";
			return false;
		}

		out.append(buffer, buffer + n);
		if(out.size() > kMaxRequestBytes) {
			error = "Request too large";
			return false;
		}

		size_t newlinePos = out.find('\n');
		if(newlinePos != string::npos) {
			out.resize(newlinePos);
			finalizeIfHasData();
			return true;
		}
	}

	if(finalizeIfHasData()) {
		return true;
	}

	error = "Server shutting down";
	return false;
}

SocketResponse SocketServer::HandleSearch(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto patternIt = cmd.params.find("pattern");
	if (patternIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing pattern parameter";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	// Parse pattern - space-separated hex bytes (e.g., "A9 00 8D")
	string patternStr = patternIt->second;
	vector<uint8_t> pattern;

	size_t pos = 0;
	while (pos < patternStr.size()) {
		// Skip whitespace
		while (pos < patternStr.size() && (patternStr[pos] == ' ' || patternStr[pos] == ',')) {
			pos++;
		}
		if (pos >= patternStr.size()) break;

		// Parse hex byte
		size_t endPos = pos;
		while (endPos < patternStr.size() && patternStr[endPos] != ' ' && patternStr[endPos] != ',') {
			endPos++;
		}

		string byteStr = patternStr.substr(pos, endPos - pos);
		if (byteStr.size() > 0) {
			// Remove 0x prefix if present
			if (byteStr.substr(0, 2) == "0x" || byteStr.substr(0, 2) == "0X") {
				byteStr = byteStr.substr(2);
			}
			try {
				pattern.push_back(static_cast<uint8_t>(std::stoul(byteStr, nullptr, 16)));
			} catch (...) {
				// Skip invalid bytes
			}
		}
		pos = endPos;
	}

	if (pattern.empty()) {
		resp.success = false;
		resp.error = "Invalid pattern";
		return resp;
	}

	// Get memory type
	MemoryType memType = MemoryType::SnesWorkRam;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
	}

	// Get search range
	uint32_t startAddr = 0;
	uint32_t endAddr = 0;

	auto startIt = cmd.params.find("start");
	auto endIt = cmd.params.find("end");

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(memType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}

	if (startIt != cmd.params.end()) {
		string s = startIt->second;
		if (s.substr(0, 2) == "0x" || s.substr(0, 2) == "0X") {
			startAddr = std::stoul(s.substr(2), nullptr, 16);
		} else {
			startAddr = std::stoul(s);
		}
	}

	if (endIt != cmd.params.end()) {
		string s = endIt->second;
		if (s.substr(0, 2) == "0x" || s.substr(0, 2) == "0X") {
			endAddr = std::stoul(s.substr(2), nullptr, 16);
		} else {
			endAddr = std::stoul(s);
		}
	} else {
		endAddr = memSize > 0 ? memSize - 1 : 0x1FFFF;
	}

	if(startAddr >= memSize) {
		resp.success = true;
		resp.data = "{\"matches\":[],\"count\":0}";
		return resp;
	}
	if(endAddr >= memSize) {
		endAddr = memSize - 1;
	}

	uint32_t patternSize = static_cast<uint32_t>(pattern.size());
	if(patternSize == 0 || endAddr < startAddr || endAddr + 1 < patternSize) {
		resp.success = true;
		resp.data = "{\"matches\":[],\"count\":0}";
		return resp;
	}

	// Search for pattern
	vector<uint32_t> matches;
	uint32_t maxMatches = 100; // Limit results

	uint32_t lastStart = endAddr - patternSize + 1;
	for (uint32_t addr = startAddr; addr <= lastStart && matches.size() < maxMatches; addr++) {
		bool found = true;
		for (size_t i = 0; i < pattern.size() && found; i++) {
			uint8_t memValue = dumper->GetMemoryValue(memType, addr + i);
			if (memValue != pattern[i]) {
				found = false;
			}
		}
		if (found) {
			matches.push_back(addr);
		}
	}

	// Build response
	stringstream ss;
	ss << "{\"matches\":[";
	for (size_t i = 0; i < matches.size(); i++) {
		if (i > 0) ss << ",";
		ss << "\"0x" << hex << uppercase << setw(6) << setfill('0') << matches[i] << "\"";
	}
	ss << "],\"count\":" << std::dec << matches.size() << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleSnapshot(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto nameIt = cmd.params.find("name");
	if (nameIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing name parameter";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	// Get memory type
	MemoryType memType = MemoryType::SnesWorkRam;
	auto memtypeIt = cmd.params.find("memtype");
	if (memtypeIt != cmd.params.end()) {
		if(!TryParseMemoryType(memtypeIt->second, memType)) {
			resp.success = false;
			resp.error = "Unknown memtype: " + memtypeIt->second;
			return resp;
		}
	}

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(memType);

	if (memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}

	// Create snapshot
	MemorySnapshot snapshot;
	snapshot.name = nameIt->second;
	snapshot.memoryType = static_cast<uint32_t>(memType);
	snapshot.timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::system_clock::now().time_since_epoch()
	).count();

	snapshot.data.resize(memSize);
	dumper->GetMemoryState(memType, snapshot.data.data());

	// Store snapshot
	{
		auto lock = _snapshotLock.AcquireSafe();
		_snapshots[snapshot.name] = std::move(snapshot);
	}

	stringstream ss;
	ss << "{\"name\":\"" << nameIt->second << "\",\"size\":" << memSize << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleDiff(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto snapshotIt = cmd.params.find("snapshot");
	if (snapshotIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing snapshot parameter";
		return resp;
	}

	// Find snapshot
	MemorySnapshot snapshot;
	{
		auto lock = _snapshotLock.AcquireSafe();
		auto it = _snapshots.find(snapshotIt->second);
		if (it == _snapshots.end()) {
			resp.success = false;
			resp.error = "Snapshot not found: " + snapshotIt->second;
			return resp;
		}
		snapshot = it->second;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	MemoryType memType = static_cast<MemoryType>(snapshot.memoryType);
	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint32_t memSize = dumper->GetMemorySize(memType);
	if(memSize == 0) {
		resp.success = false;
		resp.error = "Memory type not available or empty";
		return resp;
	}
	if(memSize != snapshot.data.size()) {
		resp.success = false;
		resp.error = "Snapshot size mismatch";
		return resp;
	}

	// Compare current memory to snapshot
	vector<uint8_t> current(snapshot.data.size());
	dumper->GetMemoryState(memType, current.data());

	// Find differences
	vector<std::tuple<uint32_t, uint8_t, uint8_t>> changes;
	uint32_t maxChanges = 1000; // Limit results

	for (size_t i = 0; i < snapshot.data.size() && changes.size() < maxChanges; i++) {
		if (snapshot.data[i] != current[i]) {
			changes.emplace_back(i, snapshot.data[i], current[i]);
		}
	}

	// Build response
	stringstream ss;
	ss << "{\"changes\":[";
	for (size_t i = 0; i < changes.size(); i++) {
		if (i > 0) ss << ",";
		auto& [addr, oldVal, newVal] = changes[i];
		ss << "{\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << addr << "\",";
		ss << "\"old\":\"0x" << setw(2) << (int)oldVal << "\",";
		ss << "\"new\":\"0x" << setw(2) << (int)newVal << "\"}";
	}
	ss << "],\"count\":" << std::dec << changes.size() << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleLabels(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	LabelManager* labelMgr = dbg.GetDebugger()->GetLabelManager();
	if (!labelMgr) {
		resp.success = false;
		resp.error = "Label manager not available";
		return resp;
	}

	// Get action (set, get, lookup, clear)
	string action = "get";
	auto actionIt = cmd.params.find("action");
	if (actionIt != cmd.params.end()) {
		action = actionIt->second;
	}

	if (action == "set") {
		// Set a label
		auto addrIt = cmd.params.find("addr");
		auto labelIt = cmd.params.find("label");

		if (addrIt == cmd.params.end() || labelIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing addr or label parameter";
			return resp;
		}

		uint32_t addr = 0;
		string addrStr = addrIt->second;
		if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
			addr = std::stoul(addrStr.substr(2), nullptr, 16);
		} else {
			addr = std::stoul(addrStr, nullptr, 16);
		}

		// Get memory type
		MemoryType memType = MemoryType::SnesWorkRam;
		auto memtypeIt = cmd.params.find("memtype");
		if (memtypeIt != cmd.params.end()) {
			if(!TryParseMemoryType(memtypeIt->second, memType)) {
				resp.success = false;
				resp.error = "Unknown memtype: " + memtypeIt->second;
				return resp;
			}
		}

		// Get comment (optional)
		string comment = "";
		auto commentIt = cmd.params.find("comment");
		if (commentIt != cmd.params.end()) {
			comment = commentIt->second;
		}

		labelMgr->SetLabel(addr, memType, labelIt->second, comment);

		resp.success = true;
		resp.data = "\"OK\"";
	}
	else if (action == "get") {
		// Get label at address
		auto addrIt = cmd.params.find("addr");
		if (addrIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing addr parameter";
			return resp;
		}

		uint32_t addr = 0;
		string addrStr = addrIt->second;
		if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
			addr = std::stoul(addrStr.substr(2), nullptr, 16);
		} else {
			addr = std::stoul(addrStr, nullptr, 16);
		}

		MemoryType memType = MemoryType::SnesWorkRam;
		auto memtypeIt = cmd.params.find("memtype");
		if (memtypeIt != cmd.params.end()) {
			if(!TryParseMemoryType(memtypeIt->second, memType)) {
				resp.success = false;
				resp.error = "Unknown memtype: " + memtypeIt->second;
				return resp;
			}
		}

		AddressInfo addrInfo;
		addrInfo.Address = addr;
		addrInfo.Type = memType;

		LabelInfo labelInfo;
		bool hasLabel = labelMgr->GetLabelAndComment(addrInfo, labelInfo);

		stringstream ss;
		ss << "{";
		if (hasLabel) {
			ss << "\"label\":\"" << JsonEscape(labelInfo.Label) << "\",";
			ss << "\"comment\":\"" << JsonEscape(labelInfo.Comment) << "\"";
		} else {
			ss << "\"label\":null";
		}
		ss << "}";

		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "lookup") {
		// Lookup address by label name
		auto labelIt = cmd.params.find("label");
		if (labelIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing label parameter";
			return resp;
		}

		string labelName = labelIt->second;
		AddressInfo addrInfo = labelMgr->GetLabelAbsoluteAddress(labelName);

		if (addrInfo.Address >= 0) {
			stringstream ss;
			ss << "{\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << addrInfo.Address << "\",";
			ss << "\"memtype\":" << std::dec << static_cast<int>(addrInfo.Type) << "}";
			resp.success = true;
			resp.data = ss.str();
		} else {
			resp.success = false;
			resp.error = "Label not found: " + labelName;
		}
	}
	else if (action == "clear") {
		labelMgr->ClearLabels();
		resp.success = true;
		resp.data = "\"OK\"";
	}
	else {
		resp.success = false;
		resp.error = "Unknown action: " + action;
	}

	return resp;
}

// ============================================================================
// Breakpoint Handlers
// ============================================================================

// This struct matches the Breakpoint class memory layout for API calls
struct BreakpointData {
	uint32_t id;
	CpuType cpuType;
	MemoryType memoryType;
	BreakpointTypeFlags type;
	int32_t startAddr;
	int32_t endAddr;
	bool enabled;
	bool markEvent;
	bool ignoreDummyOperations;
	char condition[1000];
};
static_assert(sizeof(BreakpointData) == sizeof(Breakpoint), "BreakpointData layout mismatch");

static CpuType ParseCpuType(const string& cpuType) {
	string key = NormalizeKey(cpuType);
	if (key.empty() || key == "snes") return CpuType::Snes;
	if (key == "spc") return CpuType::Spc;
	if (key == "necdsp") return CpuType::NecDsp;
	if (key == "sa1") return CpuType::Sa1;
	if (key == "gsu") return CpuType::Gsu;
	if (key == "cx4") return CpuType::Cx4;
	if (key == "gameboy" || key == "gb") return CpuType::Gameboy;
	if (key == "nes") return CpuType::Nes;
	if (key == "pce" || key == "pcengine") return CpuType::Pce;
	if (key == "sms") return CpuType::Sms;
	if (key == "gba") return CpuType::Gba;
	return CpuType::Snes;
}

void SocketServer::SyncBreakpoints(Emulator* emu) {
	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		return;
	}

	auto lock = _breakpointLock.AcquireSafe();

	// Build array of BreakpointData matching Breakpoint class layout
	vector<BreakpointData> bpData;
	bpData.reserve(_breakpoints.size());

	for (const auto& sbp : _breakpoints) {
		if (!sbp.enabled) continue;

		BreakpointData bp = {};
		bp.id = sbp.id;
		bp.cpuType = sbp.cpuType;
		bp.memoryType = sbp.memoryType;
		bp.type = static_cast<BreakpointTypeFlags>(sbp.type);
		bp.startAddr = sbp.startAddr;
		bp.endAddr = sbp.endAddr;
		bp.enabled = sbp.enabled;
		bp.markEvent = false;
		bp.ignoreDummyOperations = false;

		// Copy condition string
		if (!sbp.condition.empty()) {
			strncpy(bp.condition, sbp.condition.c_str(), sizeof(bp.condition) - 1);
			bp.condition[sizeof(bp.condition) - 1] = '\0';
		} else {
			bp.condition[0] = '\0';
		}

		bpData.push_back(bp);
	}

	// Cast and call SetBreakpoints
	// Note: BreakpointData layout matches Breakpoint class private members
	dbg.GetDebugger()->SetBreakpoints(
		reinterpret_cast<Breakpoint*>(bpData.data()),
		static_cast<uint32_t>(bpData.size())
	);
}

SocketResponse SocketServer::HandleBreakpoint(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	// Get action (add, remove, list, enable, disable, clear)
	string action = "list";
	auto actionIt = cmd.params.find("action");
	if (actionIt != cmd.params.end()) {
		action = actionIt->second;
	}

	if (action == "add") {
		// Add a new breakpoint
		auto addrIt = cmd.params.find("addr");
		if (addrIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing addr parameter";
			return resp;
		}

		uint32_t addr = 0;
		string addrStr = addrIt->second;
		if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
			addr = std::stoul(addrStr.substr(2), nullptr, 16);
		} else {
			addr = std::stoul(addrStr, nullptr, 16);
		}

		// Get breakpoint type (execute, read, write, or combination)
		// Supports: "exec", "read", "write", or shorthand like "x", "r", "w", "rw", "xrw"
		uint8_t bpType = static_cast<uint8_t>(BreakpointTypeFlags::Execute); // Default
		auto bptypeIt = cmd.params.find("bptype");
		if (bptypeIt != cmd.params.end()) {
			string typeStr = bptypeIt->second;
			bpType = 0;

			// Check for full words first (to avoid false matches from substrings)
			bool hasExec = typeStr.find("exec") != string::npos;
			bool hasRead = typeStr.find("read") != string::npos;
			bool hasWrite = typeStr.find("write") != string::npos;

			// If no full words found, check for shorthand characters
			// Only use single-char matching for short strings (e.g., "x", "rw", "xrw")
			if (!hasExec && !hasRead && !hasWrite && typeStr.length() <= 4) {
				for (char c : typeStr) {
					if (c == 'x') hasExec = true;
					if (c == 'r') hasRead = true;
					if (c == 'w') hasWrite = true;
				}
			}

			if (hasExec) bpType |= static_cast<uint8_t>(BreakpointTypeFlags::Execute);
			if (hasRead) bpType |= static_cast<uint8_t>(BreakpointTypeFlags::Read);
			if (hasWrite) bpType |= static_cast<uint8_t>(BreakpointTypeFlags::Write);

			if (bpType == 0) {
				bpType = static_cast<uint8_t>(BreakpointTypeFlags::Execute);
			}
		}

		// Get end address (optional, for address range)
		uint32_t endAddr = addr;
		auto endaddrIt = cmd.params.find("endaddr");
		if (endaddrIt != cmd.params.end()) {
			string endAddrStr = endaddrIt->second;
			if (endAddrStr.substr(0, 2) == "0x" || endAddrStr.substr(0, 2) == "0X") {
				endAddr = std::stoul(endAddrStr.substr(2), nullptr, 16);
			} else {
				endAddr = std::stoul(endAddrStr, nullptr, 16);
			}
		}

		// Get memory type
		MemoryType memType = MemoryType::SnesMemory;
		auto memtypeIt = cmd.params.find("memtype");
		if (memtypeIt != cmd.params.end()) {
			if(!TryParseMemoryType(memtypeIt->second, memType)) {
				resp.success = false;
				resp.error = "Unknown memtype: " + memtypeIt->second;
				return resp;
			}
		}

		// Get CPU type
		CpuType cpuType = CpuType::Snes;
		auto cputypeIt = cmd.params.find("cputype");
		if (cputypeIt != cmd.params.end()) {
			cpuType = ParseCpuType(cputypeIt->second);
		}

		// Get condition (optional)
		string condition = "";
		auto condIt = cmd.params.find("condition");
		if (condIt != cmd.params.end()) {
			condition = condIt->second;
		}

		// Create the breakpoint
		auto lock = _breakpointLock.AcquireSafe();
		uint32_t newId = _nextBreakpointId++;

		SocketBreakpoint sbp;
		sbp.id = newId;
		sbp.cpuType = cpuType;
		sbp.memoryType = memType;
		sbp.type = bpType;
		sbp.startAddr = static_cast<int32_t>(addr);
		sbp.endAddr = static_cast<int32_t>(endAddr);
		sbp.enabled = true;
		sbp.condition = condition;

		_breakpoints.push_back(sbp);
		lock.Release();

		// Sync with emulator
		SyncBreakpoints(emu);

		stringstream ss;
		ss << "{\"id\":" << newId << "}";
		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "remove") {
		// Remove a breakpoint by ID
		auto idIt = cmd.params.find("id");
		if (idIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing id parameter";
			return resp;
		}

		uint32_t bpId = std::stoul(idIt->second);

		auto lock = _breakpointLock.AcquireSafe();
		auto it = std::find_if(_breakpoints.begin(), _breakpoints.end(),
			[bpId](const SocketBreakpoint& bp) { return bp.id == bpId; });

		if (it != _breakpoints.end()) {
			_breakpoints.erase(it);
			lock.Release();
			SyncBreakpoints(emu);
			resp.success = true;
			resp.data = "\"OK\"";
		} else {
			resp.success = false;
			resp.error = "Breakpoint not found: " + std::to_string(bpId);
		}
	}
	else if (action == "list") {
		// List all breakpoints
		auto lock = _breakpointLock.AcquireSafe();

		stringstream ss;
		ss << "{\"breakpoints\":[";
		bool first = true;
		for (const auto& bp : _breakpoints) {
			if (!first) ss << ",";
			first = false;

			ss << "{\"id\":" << bp.id;
			ss << ",\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << bp.startAddr << "\"";
			if (bp.endAddr != bp.startAddr) {
				ss << ",\"endaddr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << bp.endAddr << "\"";
			}
			ss << std::dec;
			ss << ",\"type\":" << static_cast<int>(bp.type);
			ss << ",\"enabled\":" << (bp.enabled ? "true" : "false");
			if (!bp.condition.empty()) {
				ss << ",\"condition\":\"" << JsonEscape(bp.condition) << "\"";
			}
			ss << "}";
		}
		ss << "]}";

		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "enable" || action == "disable") {
		// Enable or disable a breakpoint
		auto idIt = cmd.params.find("id");
		if (idIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing id parameter";
			return resp;
		}

		uint32_t bpId = std::stoul(idIt->second);
		bool enable = (action == "enable");

		auto lock = _breakpointLock.AcquireSafe();
		auto it = std::find_if(_breakpoints.begin(), _breakpoints.end(),
			[bpId](const SocketBreakpoint& bp) { return bp.id == bpId; });

		if (it != _breakpoints.end()) {
			it->enabled = enable;
			lock.Release();
			SyncBreakpoints(emu);
			resp.success = true;
			resp.data = "\"OK\"";
		} else {
			resp.success = false;
			resp.error = "Breakpoint not found: " + std::to_string(bpId);
		}
	}
	else if (action == "clear") {
		// Remove all breakpoints
		auto lock = _breakpointLock.AcquireSafe();
		_breakpoints.clear();
		lock.Release();
		SyncBreakpoints(emu);

		resp.success = true;
		resp.data = "\"OK\"";
	}
	else {
		resp.success = false;
		resp.error = "Unknown action: " + action;
	}

	return resp;
}
