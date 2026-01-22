#include "pch.h"
#include "SocketServer.h"
#include "Emulator.h"
#include "DebuggerRequest.h"
#include "SaveStateManager.h"
#include "EmuSettings.h"
#include "MessageManager.h"
#include "Core/Debugger/Debugger.h"
#include "Core/Debugger/ScriptManager.h"
#include "Core/Debugger/DebugTypes.h"
#include "Core/Debugger/MemoryDumper.h"
#include "Utilities/HexUtilities.h"

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <poll.h>

using std::hex;
using std::setw;
using std::setfill;
using std::fixed;
using std::uppercase;
using std::setprecision;
using std::make_unique;

string SocketResponse::ToJson() const {
	stringstream ss;
	ss << "{\"success\":" << (success ? "true" : "false");
	if (!data.empty()) {
		ss << ",\"data\":" << data;
	}
	if (!error.empty()) {
		ss << ",\"error\":\"" << error << "\"";
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
	_handlers["PAUSE"] = HandlePause;
	_handlers["RESUME"] = HandleResume;
	_handlers["RESET"] = HandleReset;
	_handlers["READ"] = HandleRead;
	_handlers["READ16"] = HandleRead16;
	_handlers["WRITE"] = HandleWrite;
	_handlers["WRITE16"] = HandleWrite16;
	_handlers["READBLOCK"] = HandleReadBlock;
	_handlers["SAVESTATE"] = HandleSaveState;
	_handlers["LOADSTATE"] = HandleLoadState;
	_handlers["LOADSCRIPT"] = HandleLoadScript;
	_handlers["SCREENSHOT"] = HandleScreenshot;
	_handlers["CPU"] = HandleGetCpuState;
	_handlers["INPUT"] = HandleSetInput;
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
	// Read command (simple line-based protocol)
	char buffer[8192];
	ssize_t n = read(clientFd, buffer, sizeof(buffer) - 1);
	if (n <= 0) return;

	buffer[n] = '\0';
	string request(buffer);

	// Trim whitespace
	while (!request.empty() && (request.back() == '\n' || request.back() == '\r')) {
		request.pop_back();
	}

	SocketCommand cmd = ParseCommand(request);
	SocketResponse response;

	auto lock = _lock.AcquireSafe();
	auto it = _handlers.find(cmd.type);
	if (it != _handlers.end()) {
		try {
			response = it->second(_emu, cmd);
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

SocketCommand SocketServer::ParseCommand(const string& json) {
	SocketCommand cmd;

	// Very simple JSON parsing - just extract type and params
	// Format: {"type":"CMD","param1":"val1","param2":"val2"}

	size_t typeStart = json.find("\"type\"");
	if (typeStart != string::npos) {
		size_t colonPos = json.find(':', typeStart);
		size_t quoteStart = json.find('"', colonPos + 1);
		size_t quoteEnd = json.find('"', quoteStart + 1);
		if (quoteStart != string::npos && quoteEnd != string::npos) {
			cmd.type = json.substr(quoteStart + 1, quoteEnd - quoteStart - 1);
		}
	}

	// Extract other params
	auto extractParam = [&](const string& key) -> string {
		size_t keyStart = json.find("\"" + key + "\"");
		if (keyStart != string::npos) {
			size_t colonPos = json.find(':', keyStart);
			if (colonPos != string::npos) {
				// Skip whitespace
				size_t valueStart = colonPos + 1;
				while (valueStart < json.size() && (json[valueStart] == ' ' || json[valueStart] == '\t')) {
					valueStart++;
				}

				if (valueStart < json.size()) {
					if (json[valueStart] == '"') {
						// String value
						size_t quoteEnd = json.find('"', valueStart + 1);
						if (quoteEnd != string::npos) {
							return json.substr(valueStart + 1, quoteEnd - valueStart - 1);
						}
					} else {
						// Number or other value
						size_t valueEnd = valueStart;
						while (valueEnd < json.size() && json[valueEnd] != ',' && json[valueEnd] != '}') {
							valueEnd++;
						}
						return json.substr(valueStart, valueEnd - valueStart);
					}
				}
			}
		}
		return "";
	};

	// Common params
	vector<string> commonParams = {"addr", "value", "len", "slot", "path", "name", "content", "buttons", "frames", "hex"};
	for (const auto& param : commonParams) {
		string val = extractParam(param);
		if (!val.empty()) {
			cmd.params[param] = val;
		}
	}

	return cmd;
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

	// Read from SNES memory (default to work RAM mirror at 7E bank)
	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	uint8_t value = dbg.GetDebugger()->GetMemoryDumper()->GetMemoryValue(
		MemoryType::SnesMemory, addr
	);

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

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();
	uint8_t lo = dumper->GetMemoryValue(MemoryType::SnesMemory, addr);
	uint8_t hi = dumper->GetMemoryValue(MemoryType::SnesMemory, addr + 1);
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

	dbg.GetDebugger()->GetMemoryDumper()->SetMemoryValue(
		MemoryType::SnesMemory, addr, value, false
	);

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleWrite16(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto addrIt = cmd.params.find("addr");
	auto valIt = cmd.params.find("value");

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
	dumper->SetMemoryValue(MemoryType::SnesMemory, addr, value & 0xFF, false);
	dumper->SetMemoryValue(MemoryType::SnesMemory, addr + 1, (value >> 8) & 0xFF, false);

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}

SocketResponse SocketServer::HandleReadBlock(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto addrIt = cmd.params.find("addr");
	auto lenIt = cmd.params.find("len");

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

	auto dumper = dbg.GetDebugger()->GetMemoryDumper();

	stringstream ss;
	ss << "\"";
	for (uint32_t i = 0; i < len; i++) {
		uint8_t val = dumper->GetMemoryValue(MemoryType::SnesMemory, addr + i);
		ss << hex << uppercase << setw(2) << setfill('0') << (int)val;
	}
	ss << "\"";

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

	// Get the current frame buffer and encode as PNG base64
	// For now, just indicate the feature exists
	resp.success = false;
	resp.error = "Screenshot not yet implemented - use Lua script instead";
	return resp;
}

SocketResponse SocketServer::HandleGetCpuState(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	// CPU state retrieval requires console-specific handling
	// For now, return basic info
	resp.success = false;
	resp.error = "CPU state not yet implemented - use Lua script GetState() instead";
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

	// Input injection would need to go through the control manager
	// This is a placeholder for the feature
	resp.success = false;
	resp.error = "Input injection not yet implemented - use Lua script instead";
	return resp;
}
