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
#include "SNES/SnesCpuTypes.h"
#include "Shared/Video/VideoDecoder.h"
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
	_handlers["DISASM"] = HandleDisasm;
	_handlers["STEP"] = HandleStep;
	_handlers["FRAME"] = HandleRunFrame;
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
	vector<string> commonParams = {"addr", "value", "len", "slot", "path", "name", "content", "buttons", "frames", "hex", "player", "count", "mode"};
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
	IDebugger* cpuDebugger = dbg.GetDebugger()->GetCpuDebugger(cpuType);
	if (!cpuDebugger) {
		resp.success = false;
		resp.error = "CPU debugger not available";
		return resp;
	}

	// Get program counter and flags
	uint32_t pc = cpuDebugger->GetProgramCounter(true);
	uint8_t flags = cpuDebugger->GetCpuFlags();

	stringstream ss;
	ss << "{";
	ss << "\"pc\":\"0x" << hex << uppercase << setw(6) << setfill('0') << pc << "\",";
	ss << "\"flags\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)flags << "\",";

	// Get more detailed state for SNES
	if (emu->GetConsoleType() == ConsoleType::Snes) {
		// Read key registers from memory
		auto dumper = dbg.GetDebugger()->GetMemoryDumper();

		SnesCpuState& state = static_cast<SnesCpuState&>(cpuDebugger->GetState());
		uint64_t cycleCount = state.CycleCount;
		uint16_t a = state.A;
		uint16_t x = state.X;
		uint16_t y = state.Y;
		uint16_t sp = state.SP;
		uint16_t d = state.D;
		uint16_t regPc = state.PC;
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
		ss << "\"cycles\":" << dec << cycleCount << ",";
	}

	ss << "\"consoleType\":" << static_cast<int>(emu->GetConsoleType());
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

	// Set the input override via debugger
	dbg.GetDebugger()->SetInputOverrides(playerIndex, state);

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
	auto dumper = dbg.GetDebugger()->GetMemoryDumper();

	stringstream ss;
	ss << "[";

	bool first = true;
	uint32_t currentAddr = addr;
	for (uint32_t i = 0; i < count; i++) {
		// Read opcode bytes (up to 4 for SNES)
		uint8_t opcode = dumper->GetMemoryValue(MemoryType::SnesMemory, currentAddr);

		if (!first) ss << ",";
		first = false;

		ss << "{";
		ss << "\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << currentAddr << "\",";
		ss << "\"opcode\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)opcode << "\"";
		ss << "}";

		// Move to next instruction (simplified - just increment by 1 for now)
		// A proper implementation would use the instruction length
		currentAddr++;
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
	IDebugger* cpuDebugger = dbg.GetDebugger()->GetCpuDebugger(cpuType);
	if (!cpuDebugger) {
		resp.success = false;
		resp.error = "CPU debugger not available";
		return resp;
	}

	StepType stepType = StepType::Step;
	if (mode == "over") {
		stepType = StepType::StepOver;
	} else if (mode == "out") {
		stepType = StepType::StepOut;
	}

	cpuDebugger->Step(stepCount, stepType);

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
	if (dbg.GetDebugger()) {
		CpuType cpuType = emu->GetCpuTypes()[0];
		IDebugger* cpuDebugger = dbg.GetDebugger()->GetCpuDebugger(cpuType);
		if (cpuDebugger) {
			// Step by frames
			cpuDebugger->Step(frameCount, StepType::PpuStep);
		}
	} else {
		// Just run for a bit if no debugger
		emu->Resume();
	}

	resp.success = true;
	resp.data = "\"OK\"";
	return resp;
}
