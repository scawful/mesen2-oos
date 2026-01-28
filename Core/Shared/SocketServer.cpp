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
#include "Core/Debugger/ITraceLogger.h"
#include "Core/Debugger/ScriptManager.h"
#include "Core/Debugger/DebugTypes.h"
#include "Core/Debugger/Disassembler.h"
#include "Core/Debugger/MemoryDumper.h"
#include "Core/Debugger/LabelManager.h"
#include "Core/Debugger/Breakpoint.h"
#include "Core/Debugger/BreakpointManager.h"
#include "Core/Debugger/CallstackManager.h"
#include "Core/Debugger/DebugUtilities.h"
#include "SNES/SnesCpuTypes.h"
#include "Shared/TimingInfo.h"
#include "Shared/Video/VideoDecoder.h"
#include "Shared/Video/VideoRenderer.h"
#include "Utilities/HexUtilities.h"
#include "Utilities/VirtualFile.h"
#include "Utilities/FolderUtilities.h"
#include "SNES/SnesPpuTypes.h"
#include "SNES/SpcTypes.h"
#include "SNES/Coprocessors/DSP/NecDspTypes.h"
#include "SNES/Coprocessors/GSU/GsuTypes.h"
#include "SNES/Coprocessors/CX4/Cx4Types.h"
#include "YazeStateBridge.h"

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <poll.h>
#include <chrono>
#include <cctype>
#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <limits>
#include <fstream>
#include <ctime>
#include <iomanip>
#include <sstream>
#include <filesystem>

using std::hex;
using std::dec;
using std::setw;
using std::setfill;
using std::fixed;
using std::uppercase;
using std::setprecision;
using std::make_unique;

namespace fs = std::filesystem;

// Static member definitions for memory snapshots
unordered_map<string, MemorySnapshot> SocketServer::_snapshots;
SimpleLock SocketServer::_snapshotLock;

// Static member definitions for breakpoints
vector<SocketBreakpoint> SocketServer::_breakpoints;
uint32_t SocketServer::_nextBreakpointId = 1;
SimpleLock SocketServer::_breakpointLock;

// Static member definitions for P register tracking
std::deque<PRegisterChange> SocketServer::_pRegisterLog;
uint32_t SocketServer::_pRegisterLogMaxSize = 1000;
bool SocketServer::_pRegisterWatchEnabled = false;
uint8_t SocketServer::_lastPRegister = 0;
SimpleLock SocketServer::_pRegisterLock;

// Static member definitions for memory write attribution
vector<MemoryWatchRegion> SocketServer::_memoryWatches;
unordered_map<uint32_t, std::deque<MemoryWriteRecord>> SocketServer::_memoryWriteLog;
uint32_t SocketServer::_nextMemoryWatchId = 1;
SimpleLock SocketServer::_memoryWatchLock;

// Static member definitions for symbol table
unordered_map<string, SymbolEntry> SocketServer::_symbolTable;
SimpleLock SocketServer::_symbolLock;

// Static member definitions for logpoints
vector<SocketLogpoint> SocketServer::_logpoints;
std::deque<LogpointHit> SocketServer::_logpointHits;
uint32_t SocketServer::_nextLogpointId = 1;
uint32_t SocketServer::_logpointHitMaxSize = 1000;
SimpleLock SocketServer::_logpointLock;

// Static member definitions for event subscription
unordered_map<int, std::set<string>> SocketServer::_eventSubscriptions;
SimpleLock SocketServer::_eventLock;

// Static member definitions for agent registration
unordered_map<int, AgentInfo> SocketServer::_registeredAgents;
SimpleLock SocketServer::_agentLock;

// Static member definitions for command history
std::deque<CommandHistoryEntry> SocketServer::_commandHistory;
uint32_t SocketServer::_commandHistoryMaxSize = 100;
SimpleLock SocketServer::_historyLock;

// Static member definitions for save/load status
SimpleLock SocketServer::_saveLoadLock;
SimpleLock SocketServer::_saveLoadStatusLock;
SaveLoadResult SocketServer::_lastSaveResult;
SaveLoadResult SocketServer::_lastLoadResult;

// Static member definitions for validation rules
// Static member definitions for validation rules
unordered_map<string, CommandValidation> SocketServer::_validationRules;
SimpleLock SocketServer::_validationLock;

// Static member definitions for log level
SocketLogLevel SocketServer::_logLevel = SocketLogLevel::Info;
SimpleLock SocketServer::_logLevelLock;

// Static member definitions for state diff
unordered_map<string, string> SocketServer::_lastState;
SimpleLock SocketServer::_stateLock;

// Static member definitions for watch triggers
vector<WatchTrigger> SocketServer::_watchTriggers;
uint32_t SocketServer::_nextWatchTriggerId = 1;
SimpleLock SocketServer::_watchTriggerLock;

// Forward declarations for helper functions
static string NormalizeKey(string value);
static bool TryParseMemoryType(const string& memtype, MemoryType& outType);
static bool TryParseInt(const string& value, int& out);
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
static bool WriteAll(int clientFd, const string& data);
static string Base64Encode(const vector<uint8_t>& data);
static string Base64Decode(const string& encoded);
static uint64_t NowMs();
static bool ParseBoolValue(const string& value);
static bool ResolveSaveStatePath(const string& inputPath, bool allowExternal, string& resolvedPath, string& error);
static string BuildSaveLoadStatusJson(const SaveLoadResult& status);
static bool WriteFileAtomic(const string& path, const string& contents);

static bool WriteAll(int clientFd, const string& data) {
	const char* buffer = data.c_str();
	size_t total = data.size();
	size_t sent = 0;
	while(sent < total) {
		ssize_t result = 0;
#ifdef MSG_NOSIGNAL
		result = send(clientFd, buffer + sent, total - sent, MSG_NOSIGNAL);
#else
		result = write(clientFd, buffer + sent, total - sent);
#endif
		if(result < 0) {
			if(errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) {
				// Interrupted or would block, try again
				std::this_thread::sleep_for(std::chrono::milliseconds(1));
				continue;
			}
			// Connection likely reset by peer
			return false;
		}
		if(result == 0) {
			break; // Connection closed
		}
		sent += static_cast<size_t>(result);
	}
	return sent == total;
}

string SocketResponse::ToJson() const {
	stringstream ss;
	ss << "{\"success\":" << (success ? "true" : "false");
	if (!data.empty()) {
		ss << ",\"data\":" << data;
	}
	if (!error.empty()) {
		ss << ",\"error\":\"" << JsonEscape(error) << "\"";
	}
	if (errorCode != SocketErrorCode::None) {
		ss << ",\"errorCode\":" << static_cast<int>(errorCode);
	}
	if (retryable) {
		ss << ",\"retryable\":true";
	}
	ss << "}";
	return ss.str();
}

SocketServer::SocketServer(Emulator* emu) : _emu(emu), _running(false) {
	// Create socket path (allow override via env var for deterministic agents)
	const char* envPath = std::getenv("MESEN2_SOCKET_PATH");
	if (envPath && strlen(envPath) > 0) {
		_socketPath = envPath;
	} else {
		_socketPath = "/tmp/mesen2-" + std::to_string(getpid()) + ".sock";
	}
	RegisterHandlers();
}

// Initialize validation rules for commands
void SocketServer::InitializeValidationRules() {
	// Default validation: 1MB max, 50 params max
	CommandValidation defaultRule;
	defaultRule.maxRequestSize = 1024 * 1024;
	defaultRule.maxParams = 50;
	defaultRule.allowEmptyParams = true;

	// Commands that require specific parameters
	CommandValidation readRule = defaultRule;
	readRule.requiredParams.insert("addr");
	SocketServer::_validationRules["READ"] = readRule;
	SocketServer::_validationRules["READ16"] = readRule;

	CommandValidation writeRule = defaultRule;
	writeRule.requiredParams.insert("addr");
	writeRule.requiredParams.insert("value");
	SocketServer::_validationRules["WRITE"] = writeRule;
	SocketServer::_validationRules["WRITE16"] = writeRule;

	CommandValidation readBlockRule = defaultRule;
	readBlockRule.requiredParams.insert("addr");
	// Note: Handler accepts len, length, or size - validation only checks addr
	// Handler validates len/length/size presence itself (lines 1156-1159, 4829-4835)
	readBlockRule.maxRequestSize = 2 * 1024 * 1024;  // Allow larger for binary reads
	SocketServer::_validationRules["READBLOCK"] = readBlockRule;
	SocketServer::_validationRules["READBLOCK_BINARY"] = readBlockRule;

	CommandValidation breakpointRule = defaultRule;
	breakpointRule.requiredParams.insert("action");
	SocketServer::_validationRules["BREAKPOINT"] = breakpointRule;

	// Most commands can have empty params or optional params
	SocketServer::_validationRules["PING"] = defaultRule;
	SocketServer::_validationRules["STATE"] = defaultRule;
	SocketServer::_validationRules["HEALTH"] = defaultRule;
	SocketServer::_validationRules["PAUSE"] = defaultRule;
	SocketServer::_validationRules["RESUME"] = defaultRule;
	SocketServer::_validationRules["RESET"] = defaultRule;
	SocketServer::_validationRules["CPU"] = defaultRule;
	SocketServer::_validationRules["GAMESTATE"] = defaultRule;
	SocketServer::_validationRules["SPRITES"] = defaultRule;
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
	_handlers["SAVESTATE_LABEL"] = HandleSaveStateLabel;
	_handlers["LOADSTATE"] = HandleLoadState;
	_handlers["LOADSCRIPT"] = HandleLoadScript;
	_handlers["EXEC_LUA"] = HandleExecLua;
	_handlers["HELP"] = HandleHelp;
	_handlers["SCREENSHOT"] = HandleScreenshot;
	_handlers["CPU"] = HandleGetCpuState;
	_handlers["STATEINSPECT"] = HandleStateInspector;
	_handlers["INPUT"] = HandleSetInput;
	_handlers["DISASM"] = HandleDisasm;
	_handlers["STEP"] = HandleStep;
	_handlers["FRAME"] = HandleRunFrame;
        _handlers["CALLSTACK"] = HandleCallstack;
        _handlers["OSD"] = HandleOsd;
	_handlers["ROMINFO"] = HandleRomInfo;
	_handlers["REWIND"] = HandleRewind;
	_handlers["CHEAT"] = HandleCheat;
	_handlers["SPEED"] = HandleSpeed;
	_handlers["SEARCH"] = HandleSearch;
	_handlers["SNAPSHOT"] = HandleSnapshot;
	_handlers["DIFF"] = HandleDiff;
	_handlers["LABELS"] = HandleLabels;
	_handlers["BREAKPOINT"] = HandleBreakpoint;
	_handlers["LOGPOINT"] = HandleLogpoint;
	_handlers["SUBSCRIBE"] = HandleSubscribe;
	_handlers["BATCH"] = HandleBatch;
	_handlers["TRACE"] = HandleTrace;

	// P register tracking handlers
	_handlers["P_WATCH"] = HandlePWatch;
	_handlers["P_LOG"] = HandlePLog;
	_handlers["P_ASSERT"] = HandlePAssert;

	// Memory write attribution handlers
	_handlers["MEM_WATCH_WRITES"] = HandleMemWatchWrites;
	_handlers["MEM_BLAME"] = HandleMemBlame;

	// Symbol table handlers
	_handlers["SYMBOLS_LOAD"] = HandleSymbolsLoad;
	_handlers["SYMBOLS_RESOLVE"] = HandleSymbolsResolve;

	// Collision overlay handlers
	_handlers["COLLISION_OVERLAY"] = HandleCollisionOverlay;
	_handlers["COLLISION_DUMP"] = HandleCollisionDump;

	// Performance handlers
	_handlers["READBLOCK_BINARY"] = HandleReadBlockBinary;

	// API discovery handlers
	_handlers["HELP"] = HandleHelp;

	// ALTTP game state handlers
	_handlers["GAMESTATE"] = HandleGameState;
	_handlers["SPRITES"] = HandleSprites;

	// Agent discovery & health handlers
	_handlers["CAPABILITIES"] = HandleCapabilities;
	_handlers["AGENT_REGISTER"] = HandleAgentRegister;
	_handlers["METRICS"] = HandleMetrics;
	_handlers["LOG_LEVEL"] = HandleLogLevel;
	_handlers["COMMAND_HISTORY"] = HandleCommandHistory;
	_handlers["DEBUG_LOG"] = HandleDebugLog;

	// YAZE state sync handlers
	_handlers["SAVESTATE_SYNC"] = HandleSaveStateSync;
	_handlers["SAVESTATE_WATCH"] = HandleSaveStateWatch;

	// Agent-friendly feature handlers
	_handlers["STATE_DIFF"] = HandleStateDiff;
	_handlers["WATCH_TRIGGER"] = HandleWatchTrigger;

	// Initialize validation rules
	{
		auto lock = _lock.AcquireSafe();
		InitializeValidationRules();
	}
}

void SocketServer::RegisterHandler(const string& command, CommandHandler handler) {
	auto lock = _lock.AcquireSafe();
	_handlers[command] = handler;
}

// Debugger hook: Log P register changes
void SocketServer::LogPRegisterChange(uint32_t pc, uint8_t oldP, uint8_t newP, uint8_t opcode, uint64_t cycleCount) {
	if (!_pRegisterWatchEnabled) return;
	if (oldP == newP) return;  // No change

	auto lock = _pRegisterLock.AcquireSafe();

	PRegisterChange change;
	change.pc = pc;
	change.oldP = oldP;
	change.newP = newP;
	change.opcode = opcode;
	change.cycleCount = cycleCount;

	_pRegisterLog.push_back(change);

	// Trim if over max size
	while (_pRegisterLog.size() > _pRegisterLogMaxSize) {
		_pRegisterLog.pop_front();
	}
}

// Debugger hook: Check if any memory watch covers an address
bool SocketServer::HasMemoryWatch(uint32_t addr) {
	auto lock = _memoryWatchLock.AcquireSafe();
	for (const auto& watch : _memoryWatches) {
		if (addr >= watch.startAddr && addr <= watch.endAddr) {
			return true;
		}
	}
	return false;
}

// Debugger hook: Log memory writes for watched addresses
void SocketServer::LogMemoryWrite(uint32_t pc, uint32_t addr, uint16_t value, uint8_t size, uint64_t cycleCount, uint16_t stackPointer) {
	auto lock = _memoryWatchLock.AcquireSafe();

	// Check all watches to see if this address is being watched
	for (auto& watch : _memoryWatches) {
		// Check if the write overlaps with the watch region
		uint32_t writeEnd = addr + size - 1;
		if (writeEnd >= watch.startAddr && addr <= watch.endAddr) {
			// This write overlaps with the watch region
			MemoryWriteRecord record;
			record.pc = pc;
			record.addr = addr;
			record.value = value;
			record.size = size;
			record.cycleCount = cycleCount;
			record.stackPointer = stackPointer;

			_memoryWriteLog[watch.id].push_back(record);

			// Trim if over max depth
			while (_memoryWriteLog[watch.id].size() > watch.maxDepth) {
				_memoryWriteLog[watch.id].pop_front();
			}
		}
	}
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
	
	// Create initial status file
	UpdateStatusFile();
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
	
	// Remove status file
	string statusPath = GetStatusFilePath();
	unlink(statusPath.c_str());

	MessageManager::Log("[SocketServer] Stopped");
}

string SocketServer::GetStatusFilePath() const
{
	string statusPath = _socketPath;
	size_t pos = statusPath.rfind(".sock");
	if(pos != string::npos && pos == statusPath.size() - 5) {
		statusPath.replace(pos, 5, ".status");
	} else {
		statusPath += ".status";
	}
	return statusPath;
}

void SocketServer::UpdateStatusFile()
{
	string statusPath = GetStatusFilePath();

	bool emulatorRunning = _emu && _emu->IsRunning();
	uint32_t frameCount = emulatorRunning ? _emu->GetFrameCount() : 0;
	bool scriptRunning = false;
	if(_emu) {
		auto dbg = _emu->GetDebugger(false);
		if(dbg.GetDebugger() && dbg.GetDebugger()->GetScriptManager()) {
			scriptRunning = dbg.GetDebugger()->GetScriptManager()->HasScript();
		}
	}
	size_t agentCount = 0;
	{
		auto lock = _agentLock.AcquireSafe();
		agentCount = _registeredAgents.size();
	}

	SaveLoadResult lastSave;
	SaveLoadResult lastLoad;
	{
		auto lock = _saveLoadStatusLock.AcquireSafe();
		lastSave = _lastSaveResult;
		lastLoad = _lastLoadResult;
	}

	stringstream ss;
	ss << "{";
	ss << "\"pid\":" << getpid() << ",";
	ss << "\"socketPath\":\"" << JsonEscape(_socketPath) << "\",";
	ss << "\"statusPath\":\"" << JsonEscape(statusPath) << "\",";
	ss << "\"emulatorRunning\":" << (emulatorRunning ? "true" : "false") << ",";
	ss << "\"romHash\":\"" << (_emu ? _emu->GetHash(HashType::Sha1) : "") << "\",";
	ss << "\"paused\":" << ((_emu && _emu->IsPaused()) ? "true" : "false") << ",";
	ss << "\"frameCount\":" << frameCount << ",";
	ss << "\"scriptRunning\":" << (scriptRunning ? "true" : "false") << ",";
	ss << "\"registeredAgents\":" << agentCount << ",";
	ss << "\"lastSave\":" << BuildSaveLoadStatusJson(lastSave) << ",";
	ss << "\"lastLoad\":" << BuildSaveLoadStatusJson(lastLoad);
	ss << "}";

	WriteFileAtomic(statusPath, ss.str());
}

void SocketServer::ServerLoop() {
	vector<struct pollfd> pfds;
	pfds.push_back({ _serverFd, POLLIN, 0 });
	auto lastStatusUpdate = std::chrono::steady_clock::now();

	while (_running) {
		int ret = poll(pfds.data(), (nfds_t)pfds.size(), 100); // 100ms timeout
		if (ret < 0) {
			if (errno != EINTR) {
				break;
			}
			continue;
		}

		auto now = std::chrono::steady_clock::now();
		if (now - lastStatusUpdate >= std::chrono::seconds(1)) {
			UpdateStatusFile();
			lastStatusUpdate = now;
		}

		if (ret == 0) continue; // Timeout, check _running flag

	// Handle new connections
	if (pfds[0].revents & POLLIN) {
		int clientFd = accept(_serverFd, nullptr, nullptr);
		if (clientFd >= 0) {
			// Set non-blocking for persistent connections
			int flags = fcntl(clientFd, F_GETFL, 0);
			if(flags == -1 || fcntl(clientFd, F_SETFL, flags | O_NONBLOCK) == -1) {
				close(clientFd);
				continue;
			}
#ifdef SO_NOSIGPIPE
			int noSigPipe = 1;
			setsockopt(clientFd, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, sizeof(noSigPipe));
#endif
			pfds.push_back({ clientFd, POLLIN, 0 });
		}
	}

		// Handle existing clients
		for (size_t i = 1; i < pfds.size(); ) {
			if (pfds[i].revents & POLLIN) {
				if (!HandleClient(pfds[i].fd)) {
					// Connection should be closed
					close(pfds[i].fd);

					// Remove from event clients if it was subscribed
					{
						auto lock = _eventLock.AcquireSafe();
						_eventSubscriptions.erase(pfds[i].fd);
					}

					pfds.erase(pfds.begin() + i);
					continue;
				}
			} else if (pfds[i].revents & (POLLERR | POLLHUP | POLLNVAL)) {
				close(pfds[i].fd);

				// Remove from event clients
				{
					auto lock = _eventLock.AcquireSafe();
					_eventSubscriptions.erase(pfds[i].fd);
				}

				pfds.erase(pfds.begin() + i);
				continue;
			}
			i++;
		}
	}

	// Cleanup remaining
	for (size_t i = 1; i < pfds.size(); i++) {
		close(pfds[i].fd);
	}
}

bool SocketServer::HandleClient(int clientFd) {
	auto startTime = std::chrono::steady_clock::now();
	string request;
	string readError;
	
	if (!ReadRequestLine(clientFd, _running, request, readError)) {
		if (!readError.empty()) {
			SocketResponse response;
			response.success = false;
			response.error = readError;
			response.errorCode = SocketErrorCode::ConnectionError;
			string responseJson = response.ToJson() + "\n";
			if (!WriteAll(clientFd, responseJson)) {
				return false; // Failure to write error response, close connection
			}
		}
		return false;
	}

	SocketCommand cmd;
	cmd.clientFd = clientFd;
	string parseError;
	if (!ParseCommand(request, cmd, parseError)) {
		SocketResponse response;
		response.success = false;
		response.error = parseError.empty() ? "Invalid request" : parseError;
		response.errorCode = SocketErrorCode::InvalidRequest;
		string responseJson = response.ToJson() + "\n";
		WriteAll(clientFd, responseJson);
		return true; // Keep open, maybe next command is valid
	}

	// Validate command before processing
	string validationError;
	SocketErrorCode validationErrorCode = SocketErrorCode::None;
	if (!ValidateCommand(cmd, validationError, validationErrorCode)) {
		SocketResponse response;
		response.success = false;
		response.error = validationError;
		response.errorCode = validationErrorCode;
		string responseJson = response.ToJson() + "\n";
		WriteAll(clientFd, responseJson);
		return true; // Keep open, validation errors are retryable
	}

	CommandHandler handler;
	{
		auto lock = _lock.AcquireSafe();
		auto it = _handlers.find(cmd.type);
		if (it != _handlers.end()) {
			handler = it->second;
		}
	}

	SocketResponse response;
	if (handler) {
		try {
			response = handler(_emu, cmd);
		} catch (const std::exception& e) {
			response.success = false;
			response.error = string("Internal error: ") + e.what();
			response.errorCode = SocketErrorCode::InternalError;
			response.retryable = false;
		} catch (...) {
			response.success = false;
			response.error = "Unknown internal error";
			response.errorCode = SocketErrorCode::InternalError;
			response.retryable = false;
		}
	} else {
		response.success = false;
		response.error = "Unknown command: " + cmd.type;
		response.errorCode = SocketErrorCode::CommandNotFound;
		response.retryable = false;
	}

	// Calculate latency
	auto endTime = std::chrono::steady_clock::now();
	auto latencyUs = std::chrono::duration_cast<std::chrono::microseconds>(endTime - startTime).count();

	// Record command history
	{
		auto lock = _historyLock.AcquireSafe();
		CommandHistoryEntry entry;
		entry.command = cmd.type;
		auto now = std::chrono::system_clock::now();
		auto timeT = std::chrono::system_clock::to_time_t(now);
		char timeStr[64];
		std::strftime(timeStr, sizeof(timeStr), "%Y-%m-%d %H:%M:%S", std::localtime(&timeT));
		entry.timestamp = timeStr;
		entry.errorCode = response.errorCode;
		entry.latencyUs = latencyUs;
		
		_commandHistory.push_back(entry);
		while (_commandHistory.size() > _commandHistoryMaxSize) {
			_commandHistory.pop_front();
		}
	}

	string responseJson = response.ToJson() + "\n";
	WriteAll(clientFd, responseJson);
	
	// If the command was SUBSCRIBE, we definitely keep it open,
	// but generally we keep all open now until they disconnect.
	return true;
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

bool SocketServer::ValidateCommand(const SocketCommand& cmd, string& error, SocketErrorCode& errorCode) {
	auto lock = _validationLock.AcquireSafe();
	auto it = _validationRules.find(cmd.type);
	
	// If no specific rule, use default validation
	CommandValidation rule;
	if (it != _validationRules.end()) {
		rule = it->second;
	} else {
		// Default rule
		rule.maxRequestSize = 1024 * 1024;
		rule.maxParams = 50;
		rule.allowEmptyParams = true;
	}

	// Check parameter count
	if (cmd.params.size() > rule.maxParams) {
		error = "Too many parameters (max: " + std::to_string(rule.maxParams) + ")";
		errorCode = SocketErrorCode::InvalidParameter;
		return false;
	}

	// Check required parameters
	for (const auto& required : rule.requiredParams) {
		if (cmd.params.find(required) == cmd.params.end()) {
			error = "Missing required parameter: " + required;
			errorCode = SocketErrorCode::MissingParameter;
			return false;
		}
	}

	// Check if empty params are allowed
	if (!rule.allowEmptyParams && cmd.params.empty()) {
		error = "Command requires parameters";
		errorCode = SocketErrorCode::MissingParameter;
		return false;
	}

	errorCode = SocketErrorCode::None;
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

	SaveLoadResult lastSave;
	SaveLoadResult lastLoad;
	{
		auto lock = _saveLoadStatusLock.AcquireSafe();
		lastSave = _lastSaveResult;
		lastLoad = _lastLoadResult;
	}
	ss << ",\"lastSave\":" << BuildSaveLoadStatusJson(lastSave);
	ss << ",\"lastLoad\":" << BuildSaveLoadStatusJson(lastLoad);

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
	bool scriptRunning = false;
	if(running) {
		auto dbg = emu->GetDebugger(false);
		if(dbg.GetDebugger() && dbg.GetDebugger()->GetScriptManager()) {
			scriptRunning = dbg.GetDebugger()->GetScriptManager()->HasScript();
		}
	}

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

	// Get additional diagnostics
	size_t agentCount = 0;
	size_t subscriptionCount = 0;
	uint64_t totalCommands = 0;
	{
		auto lock = _agentLock.AcquireSafe();
		agentCount = _registeredAgents.size();
	}
	{
		auto lock = _eventLock.AcquireSafe();
		subscriptionCount = _eventSubscriptions.size();
	}
	{
		auto lock = _historyLock.AcquireSafe();
		totalCommands = _commandHistory.size();
	}

	// Get YAZE sync status
	string lastYazeState = YazeStateBridge::GetLastSyncedState();
	uint64_t lastYazeFrame = YazeStateBridge::GetLastSyncedFrame();
	string lastYazeError = YazeStateBridge::GetLastError();
	uint64_t lastYazeErrorTime = YazeStateBridge::GetLastErrorTimeMs();

	stringstream ss;
	ss << "{";
	ss << "\"running\":" << (running ? "true" : "false") << ",";
	ss << "\"paused\":" << (paused ? "true" : "false") << ",";
	ss << "\"debugging\":" << (debugging ? "true" : "false") << ",";
	ss << "\"scriptRunning\":" << (scriptRunning ? "true" : "false") << ",";
	ss << "\"consoleType\":" << static_cast<int>(emu->GetConsoleType()) << ",";
	ss << "\"cpuType\":" << (running ? std::to_string(static_cast<int>(cpuType)) : "null") << ",";
	ss << "\"pc\":" << pcValue << ",";
	ss << "\"disasmOk\":" << (disasmOk ? "true" : "false") << ",";
	ss << "\"disasm\":" << (disasmOk ? disasmData : "null") << ",";
	ss << "\"watchHudText\":\"" << JsonEscape(watchHudText) << "\",";
	ss << "\"diagnostics\":{";
	ss << "\"registeredAgents\":" << agentCount << ",";
	ss << "\"activeSubscriptions\":" << subscriptionCount << ",";
	ss << "\"totalCommands\":" << totalCommands << ",";
	ss << "\"yazeSync\":{";
	ss << "\"lastState\":\"" << JsonEscape(lastYazeState) << "\",";
	ss << "\"lastFrame\":" << lastYazeFrame << ",";
	if(lastYazeError.empty()) {
		ss << "\"lastError\":null,";
	} else {
		ss << "\"lastError\":\"" << JsonEscape(lastYazeError) << "\",";
	}
	ss << "\"lastErrorTimeMs\":" << lastYazeErrorTime;
	ss << "}";
	ss << "}";
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

	if(!emu || !emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		resp.errorCode = SocketErrorCode::EmulatorNotRunning;
		{
			auto lock = _saveLoadStatusLock.AcquireSafe();
			_lastSaveResult.valid = true;
			_lastSaveResult.success = false;
			_lastSaveResult.path.clear();
			_lastSaveResult.error = resp.error;
			_lastSaveResult.frame = 0;
			_lastSaveResult.timestampMs = NowMs();
		}
		return resp;
	}

	auto slotIt = cmd.params.find("slot");
	auto pathIt = cmd.params.find("path");
	auto labelIt = cmd.params.find("label");
	auto pauseIt = cmd.params.find("pause");
	auto allowExternalIt = cmd.params.find("allow_external");
	if(allowExternalIt == cmd.params.end()) {
		allowExternalIt = cmd.params.find("allowExternal");
	}

	bool pause = false;
	if (pauseIt != cmd.params.end()) {
		pause = ParseBoolValue(pauseIt->second);
	}

	bool allowExternal = false;
	if (allowExternalIt != cmd.params.end()) {
		allowExternal = ParseBoolValue(allowExternalIt->second);
	}

	string statePath;
	string errorMessage;

	auto saveLoadLock = _saveLoadLock.AcquireSafe();
	bool wasPaused = emu->IsPaused();
	if(pause && !wasPaused) {
		emu->Pause();
	}

	if (slotIt != cmd.params.end()) {
		int slot = 0;
		if(!TryParseInt(slotIt->second, slot) || slot <= 0) {
			resp.success = false;
			resp.error = "Invalid slot value";
			resp.errorCode = SocketErrorCode::InvalidParameter;
			errorMessage = resp.error;
		}
		else {
			statePath = emu->GetSaveStateManager()->GetStateFilepath(slot);
			bool saved = emu->GetSaveStateManager()->SaveState(statePath, false);
			resp.success = saved;
			if(saved) {
				resp.data = "\"OK\"";
				YazeStateBridge::NotifyStateSaved(statePath, emu->GetFrameCount());
				if(labelIt != cmd.params.end()) {
					SaveStateManager::SetStateLabel(statePath, labelIt->second);
				}
			} else {
				resp.error = "Failed to save state to slot";
				resp.errorCode = SocketErrorCode::InternalError;
				errorMessage = resp.error;
			}
		}
	} else if (pathIt != cmd.params.end()) {
		string resolvedPath;
		string validationError;
		if(!ResolveSaveStatePath(pathIt->second, allowExternal, resolvedPath, validationError)) {
			resp.success = false;
			resp.error = validationError;
			resp.errorCode = SocketErrorCode::PermissionDenied;
			errorMessage = resp.error;
		} else {
			statePath = resolvedPath;
			bool saved = emu->GetSaveStateManager()->SaveState(statePath, false);
			resp.success = saved;
			if(saved) {
				resp.data = "\"OK\"";
				YazeStateBridge::NotifyStateSaved(statePath, emu->GetFrameCount());
				if(labelIt != cmd.params.end()) {
					SaveStateManager::SetStateLabel(statePath, labelIt->second);
				}
			} else {
				resp.error = "Failed to save state to file";
				resp.errorCode = SocketErrorCode::InternalError;
				errorMessage = resp.error;
			}
		}
	} else {
		resp.success = false;
		resp.error = "Missing slot or path parameter";
		resp.errorCode = SocketErrorCode::MissingParameter;
		errorMessage = resp.error;
	}

	if(pause && !wasPaused) {
		emu->Resume();
	}

	{
		auto lock = _saveLoadStatusLock.AcquireSafe();
		_lastSaveResult.valid = true;
		_lastSaveResult.success = resp.success;
		_lastSaveResult.path = statePath;
		_lastSaveResult.error = errorMessage;
		_lastSaveResult.frame = emu->GetFrameCount();
		_lastSaveResult.timestampMs = NowMs();
	}

	return resp;
}

SocketResponse SocketServer::HandleLoadState(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if(!emu || !emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		resp.errorCode = SocketErrorCode::EmulatorNotRunning;
		{
			auto lock = _saveLoadStatusLock.AcquireSafe();
			_lastLoadResult.valid = true;
			_lastLoadResult.success = false;
			_lastLoadResult.path.clear();
			_lastLoadResult.error = resp.error;
			_lastLoadResult.frame = 0;
			_lastLoadResult.timestampMs = NowMs();
		}
		return resp;
	}

	auto slotIt = cmd.params.find("slot");
	auto pathIt = cmd.params.find("path");
	auto pauseIt = cmd.params.find("pause");
	auto allowExternalIt = cmd.params.find("allow_external");
	if(allowExternalIt == cmd.params.end()) {
		allowExternalIt = cmd.params.find("allowExternal");
	}

	bool pause = false;
	if (pauseIt != cmd.params.end()) {
		pause = ParseBoolValue(pauseIt->second);
	}

	bool allowExternal = false;
	if (allowExternalIt != cmd.params.end()) {
		allowExternal = ParseBoolValue(allowExternalIt->second);
	}

	string statePath;
	string errorMessage;

	auto saveLoadLock = _saveLoadLock.AcquireSafe();
	bool wasPaused = emu->IsPaused();
	if(pause && !wasPaused) {
		emu->Pause();
	}

	if (slotIt != cmd.params.end()) {
		int slot = 0;
		if(!TryParseInt(slotIt->second, slot) || slot <= 0) {
			resp.success = false;
			resp.error = "Invalid slot value";
			resp.errorCode = SocketErrorCode::InvalidParameter;
			errorMessage = resp.error;
		} else {
			statePath = emu->GetSaveStateManager()->GetStateFilepath(slot);
			bool success = emu->GetSaveStateManager()->LoadState(slot);
			resp.success = success;
			resp.data = success ? "\"OK\"" : "";
			if (!success) {
				resp.error = "Failed to load state from slot";
				resp.errorCode = SocketErrorCode::InvalidState;
				errorMessage = resp.error;
			}
		}
	} else if (pathIt != cmd.params.end()) {
		string resolvedPath;
		string validationError;
		if(!ResolveSaveStatePath(pathIt->second, allowExternal, resolvedPath, validationError)) {
			resp.success = false;
			resp.error = validationError;
			resp.errorCode = SocketErrorCode::PermissionDenied;
			errorMessage = resp.error;
		} else {
			statePath = resolvedPath;
			bool success = emu->GetSaveStateManager()->LoadState(statePath);
			resp.success = success;
			resp.data = success ? "\"OK\"" : "";
			if (!success) {
				resp.error = "Failed to load state from file";
				resp.errorCode = SocketErrorCode::InvalidState;
				errorMessage = resp.error;
			}
		}
	} else {
		resp.success = false;
		resp.error = "Missing slot or path parameter";
		resp.errorCode = SocketErrorCode::MissingParameter;
		errorMessage = resp.error;
	}

	if(pause && !wasPaused) {
		emu->Resume();
	}

	{
		auto lock = _saveLoadStatusLock.AcquireSafe();
		_lastLoadResult.valid = true;
		_lastLoadResult.success = resp.success;
		_lastLoadResult.path = statePath;
		_lastLoadResult.error = errorMessage;
		_lastLoadResult.frame = emu->GetFrameCount();
		_lastLoadResult.timestampMs = NowMs();
	}

	return resp;
}

SocketResponse SocketServer::HandleSaveStateLabel(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	string action = cmd.GetParam("action", "get");
	action = NormalizeKey(action);
	if(action.empty()) {
		action = "get";
	}

	auto slotIt = cmd.params.find("slot");
	auto pathIt = cmd.params.find("path");

	string statePath;
	int slotValue = 0;
	bool hasSlot = slotIt != cmd.params.end();

	if(hasSlot) {
		if(!emu || !emu->IsRunning()) {
			resp.success = false;
			resp.error = "No ROM loaded";
			resp.errorCode = SocketErrorCode::EmulatorNotRunning;
			return resp;
		}

		if(!TryParseInt(slotIt->second, slotValue) || slotValue <= 0) {
			resp.success = false;
			resp.error = "Invalid slot value";
			resp.errorCode = SocketErrorCode::InvalidParameter;
			return resp;
		}
		statePath = emu->GetSaveStateManager()->GetStateFilepath(slotValue);
	} else if(pathIt != cmd.params.end()) {
		statePath = pathIt->second;
	} else {
		resp.success = false;
		resp.error = "Missing slot or path parameter";
		resp.errorCode = SocketErrorCode::MissingParameter;
		return resp;
	}

	if(action == "get") {
		string label = SaveStateManager::GetStateLabel(statePath);
		stringstream ss;
		ss << "{";
		if(hasSlot) {
			ss << "\"slot\":" << slotValue << ",";
		}
		ss << "\"path\":\"" << JsonEscape(statePath) << "\",";
		if(label.empty()) {
			ss << "\"label\":null";
		} else {
			ss << "\"label\":\"" << JsonEscape(label) << "\"";
		}
		ss << "}";

		resp.success = true;
		resp.data = ss.str();
		return resp;
	}

	if(action == "set") {
		auto labelIt = cmd.params.find("label");
		if(labelIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing label parameter";
			resp.errorCode = SocketErrorCode::MissingParameter;
			return resp;
		}

		if(!SaveStateManager::SetStateLabel(statePath, labelIt->second)) {
			resp.success = false;
			resp.error = "Failed to write label";
			resp.errorCode = SocketErrorCode::InternalError;
			return resp;
		}

		resp.success = true;
		resp.data = "\"OK\"";
		return resp;
	}

	if(action == "clear") {
		if(!SaveStateManager::ClearStateLabel(statePath)) {
			resp.success = false;
			resp.error = "Failed to clear label";
			resp.errorCode = SocketErrorCode::InternalError;
			return resp;
		}

		resp.success = true;
		resp.data = "\"OK\"";
		return resp;
	}

	resp.success = false;
	resp.error = "Invalid action: " + action;
	resp.errorCode = SocketErrorCode::InvalidParameter;
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

SocketResponse SocketServer::HandleExecLua(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto codeIt = cmd.params.find("code");
	if (codeIt == cmd.params.end()) {
		// Legacy scripts might use "content" or no key if it was the only param
		codeIt = cmd.params.find("content");
	}

	if (codeIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing Lua code (base64 encoded)";
		return resp;
	}

	string decoded = Base64Decode(codeIt->second);
	if (decoded.empty() && !codeIt->second.empty()) {
		resp.success = false;
		resp.error = "Failed to decode base64 code";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	// EXEC_LUA runs the script once and removes it, or uses a persistent "exec" slot.
	// We use -1 to create a new script host for this execution.
	int32_t scriptId = dbg.GetDebugger()->GetScriptManager()->LoadScript("exec_lua", "", decoded, -1);

	resp.success = scriptId >= 0;
	if (resp.success) {
		resp.data = std::to_string(scriptId);
	} else {
		resp.error = "Failed to execute Lua code";
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

	// Decide which CPU to sample (default = first/primary CPU)
	CpuType cpuType = emu->GetCpuTypes()[0];
	auto cpuParam = cmd.params.find("cputype");
	if (cpuParam != cmd.params.end()) {
		string ct = cpuParam->second;
		std::transform(ct.begin(), ct.end(), ct.begin(), ::tolower);
		if (ct == "spc" || ct == "apu") {
			cpuType = CpuType::Spc;
		} else if (ct == "snes" || ct == "cpu" || ct == "main") {
			cpuType = emu->GetCpuTypes()[0];
		}
		// If requested CPU is not available, error out
		bool found = false;
		for (auto c : emu->GetCpuTypes()) {
			if (c == cpuType) {
				found = true;
				break;
			}
		}
		if (!found) {
			resp.success = false;
			resp.error = "Requested CPU type not available";
			return resp;
		}
	}

	Debugger* debugger = dbg.GetDebugger();

	// Get program counter and flags using Debugger's direct methods
	uint32_t pc = debugger->GetProgramCounter(cpuType, true);
	uint8_t flags = debugger->GetCpuFlags(cpuType);

	stringstream ss;
	ss << "{";
	ss << "\"pc\":\"0x" << hex << uppercase << setw(6) << setfill('0') << pc << "\",";
	ss << "\"flags\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)flags << "\",";

	// Get more detailed state per CPU type
	if (cpuType == CpuType::Spc) {
		SpcState& spc = static_cast<SpcState&>(debugger->GetCpuStateRef(cpuType));
		uint64_t cycleCount = spc.Cycle;
		ss << "\"a\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)spc.A << "\",";
		ss << "\"x\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)spc.X << "\",";
		ss << "\"y\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)spc.Y << "\",";
		ss << "\"sp\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)spc.SP << "\",";
		ss << "\"p\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)spc.PS << "\",";
		ss << "\"cycles\":" << std::dec << cycleCount << ",";
	}
	else if (emu->GetConsoleType() == ConsoleType::Snes) {
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
	bool includeGameState = false;
	auto includeIt = cmd.params.find("includeGameState");
	if (includeIt != cmd.params.end()) {
		includeGameState = ParseBoolValue(includeIt->second);
	}
	auto gameStateIt = cmd.params.find("gamestate");
	if (gameStateIt != cmd.params.end()) {
		includeGameState = includeGameState || ParseBoolValue(gameStateIt->second);
	}

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

	SaveLoadResult lastSave;
	SaveLoadResult lastLoad;
	{
		auto lock = _saveLoadStatusLock.AcquireSafe();
		lastSave = _lastSaveResult;
		lastLoad = _lastLoadResult;
	}
	ss << ",\"lastSave\":" << BuildSaveLoadStatusJson(lastSave);
	ss << ",\"lastLoad\":" << BuildSaveLoadStatusJson(lastLoad);

	if(includeGameState) {
		SocketCommand gameCmd;
		gameCmd.type = "GAMESTATE";
		SocketResponse gameResp = HandleGameState(emu, gameCmd);
		if(gameResp.success) {
			ss << ",\"gameState\":" << gameResp.data;
		} else if(!gameResp.error.empty()) {
			ss << ",\"gameStateError\":\"" << JsonEscape(gameResp.error) << "\"";
		}
	}

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

	auto parseUInt32 = [](const string& value, uint32_t& out) -> bool {
		try {
			size_t idx = 0;
			unsigned long parsed = std::stoul(value, &idx, 10);
			if(idx != value.size()) {
				return false;
			}
			if(parsed > std::numeric_limits<uint32_t>::max()) {
				return false;
			}
			out = static_cast<uint32_t>(parsed);
			return true;
		} catch(...) {
			return false;
		}
	};

	// Get player index (default to player 1 / index 0)
	uint32_t playerIndex = 0;
	auto indexIt = cmd.params.find("player");
	if (indexIt != cmd.params.end()) {
		if(!parseUInt32(indexIt->second, playerIndex)) {
			resp.success = false;
			resp.error = "Invalid player parameter";
			return resp;
		}
		if (playerIndex > 7) playerIndex = 0;
	}

	// Get frame count (default 0 = indefinite for backward compatibility)
	uint32_t frameCount = 0;
	auto framesIt = cmd.params.find("frames");
	if (framesIt != cmd.params.end()) {
		if(!parseUInt32(framesIt->second, frameCount)) {
			resp.success = false;
			resp.error = "Invalid frames parameter";
			return resp;
		}
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

static uint64_t NowMs()
{
	return std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::system_clock::now().time_since_epoch()
	).count();
}

static bool ParseBoolValue(const string& value)
{
	string key = NormalizeKey(value);
	return key == "true" || key == "1" || key == "yes" || key == "on";
}

static bool IsSubPath(const fs::path& base, const fs::path& candidate)
{
	auto baseIt = base.begin();
	auto candIt = candidate.begin();
	for(; baseIt != base.end(); ++baseIt, ++candIt) {
		if(candIt == candidate.end() || baseIt->string() != candIt->string()) {
			return false;
		}
	}
	return true;
}

static bool ResolveSaveStatePath(const string& inputPath, bool allowExternal, string& resolvedPath, string& error)
{
	if(inputPath.empty()) {
		error = "Missing path parameter";
		return false;
	}

	string saveFolder = FolderUtilities::GetSaveStateFolder();
	fs::path path = fs::u8path(inputPath);
	if(path.is_relative()) {
		path = fs::u8path(FolderUtilities::CombinePath(saveFolder, inputPath));
	}

	std::error_code ec;
	fs::path normalizedPath = fs::weakly_canonical(path, ec);
	if(ec) {
		normalizedPath = fs::absolute(path, ec);
	}
	if(ec) {
		normalizedPath = path.lexically_normal();
	}

	fs::path normalizedFolder = fs::weakly_canonical(fs::u8path(saveFolder), ec);
	if(ec) {
		normalizedFolder = fs::absolute(fs::u8path(saveFolder), ec);
	}
	if(ec) {
		normalizedFolder = fs::u8path(saveFolder).lexically_normal();
	}

	if(!allowExternal && !IsSubPath(normalizedFolder, normalizedPath)) {
		error = "Path must be within save state folder (set allow_external=true to override)";
		return false;
	}

	resolvedPath = normalizedPath.u8string();
	return true;
}

static string BuildSaveLoadStatusJson(const SaveLoadResult& status)
{
	if(!status.valid) {
		return "null";
	}

	stringstream ss;
	ss << "{";
	ss << "\"success\":" << (status.success ? "true" : "false") << ",";
	ss << "\"path\":\"" << JsonEscape(status.path) << "\",";
	ss << "\"frame\":" << status.frame << ",";
	ss << "\"timestampMs\":" << status.timestampMs;
	if(!status.error.empty()) {
		ss << ",\"error\":\"" << JsonEscape(status.error) << "\"";
	}
	ss << "}";
	return ss.str();
}

static bool WriteFileAtomic(const string& path, const string& contents)
{
	fs::path target = fs::u8path(path);
	fs::path temp = target;
	temp += ".tmp";

	std::ofstream out(temp, std::ios::out | std::ios::trunc);
	if(!out.is_open()) {
		return false;
	}
	out << contents;
	out.flush();
	if(!out.good()) {
		out.close();
		std::error_code cleanupError;
		fs::remove(temp, cleanupError);
		return false;
	}
	out.close();

	std::error_code renameError;
	fs::rename(temp, target, renameError);
	if(renameError) {
		std::error_code removeError;
		fs::remove(target, removeError);
		fs::rename(temp, target, renameError);
	}
	if(renameError) {
		std::error_code cleanupError;
		fs::remove(temp, cleanupError);
		return false;
	}
	return true;
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

static bool TryParseInt(const string& value, int& out)
{
	try {
		size_t idx = 0;
		int parsed = std::stoi(value, &idx, 0);
		if(idx != value.size()) {
			return false;
		}
		out = parsed;
		return true;
	} catch(...) {
		return false;
	}
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

SocketResponse SocketServer::HandleBatch(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	// Parse commands array from params
	auto commandsIt = cmd.params.find("commands");
	if (commandsIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing commands parameter";
		return resp;
	}

	// The commands value should be a JSON array string
	// Format: [{"type":"PING"},{"type":"READ","addr":"0x7E0000"}]
	string commandsJson = commandsIt->second;

	// Parse JSON array of commands
	vector<SocketCommand> subCommands;
	string parseError;

	// Simple JSON array parsing
	size_t pos = 0;
	while (pos < commandsJson.size() && commandsJson[pos] != '[') pos++;
	if (pos >= commandsJson.size()) {
		resp.success = false;
		resp.error = "Invalid commands format - expected JSON array";
		return resp;
	}
	pos++; // Skip '['

	while (pos < commandsJson.size()) {
		// Skip whitespace
		while (pos < commandsJson.size() && std::isspace(commandsJson[pos])) pos++;

		if (pos >= commandsJson.size() || commandsJson[pos] == ']') break;

		// Find the start of an object
		if (commandsJson[pos] != '{') {
			if (commandsJson[pos] == ',') {
				pos++;
				continue;
			}
			break;
		}

		// Find matching closing brace
		int braceCount = 1;
		size_t start = pos;
		pos++;
		while (pos < commandsJson.size() && braceCount > 0) {
			if (commandsJson[pos] == '{') braceCount++;
			else if (commandsJson[pos] == '}') braceCount--;
			pos++;
		}

		if (braceCount != 0) {
			resp.success = false;
			resp.error = "Invalid commands format - unmatched braces";
			return resp;
		}

		string subJson = commandsJson.substr(start, pos - start);

		// Parse the sub-command
		SocketCommand subCmd;
		string subError;
		unordered_map<string, string> subParams;
		if (!ParseJsonObject(subJson, subParams, subError)) {
			resp.success = false;
			resp.error = "Failed to parse sub-command: " + subError;
			return resp;
		}

		auto typeIt = subParams.find("type");
		if (typeIt == subParams.end()) {
			resp.success = false;
			resp.error = "Sub-command missing type field";
			return resp;
		}

		subCmd.type = typeIt->second;
		std::transform(subCmd.type.begin(), subCmd.type.end(), subCmd.type.begin(),
			[](unsigned char c) { return static_cast<char>(std::toupper(c)); });

		subParams.erase(typeIt);
		subCmd.params = std::move(subParams);
		subCommands.push_back(std::move(subCmd));
	}

	if (subCommands.empty()) {
		resp.success = false;
		resp.error = "No commands in batch";
		return resp;
	}

	// Execute each sub-command and collect results
	stringstream ss;
	ss << "{\"results\":[";

	bool allSuccess = true;
	for (size_t i = 0; i < subCommands.size(); i++) {
		const SocketCommand& subCmd = subCommands[i];

		if (i > 0) ss << ",";

		SocketResponse subResp;
		if (subCmd.type == "PING") subResp = HandlePing(emu, subCmd);
		else if (subCmd.type == "STATE") subResp = HandleState(emu, subCmd);
		else if (subCmd.type == "HEALTH") subResp = HandleHealth(emu, subCmd);
		else if (subCmd.type == "PAUSE") subResp = HandlePause(emu, subCmd);
		else if (subCmd.type == "RESUME") subResp = HandleResume(emu, subCmd);
		else if (subCmd.type == "RESET") subResp = HandleReset(emu, subCmd);
		else if (subCmd.type == "READ") subResp = HandleRead(emu, subCmd);
		else if (subCmd.type == "READ16") subResp = HandleRead16(emu, subCmd);
		else if (subCmd.type == "WRITE") subResp = HandleWrite(emu, subCmd);
		else if (subCmd.type == "WRITE16") subResp = HandleWrite16(emu, subCmd);
		else if (subCmd.type == "READBLOCK") subResp = HandleReadBlock(emu, subCmd);
		else if (subCmd.type == "WRITEBLOCK") subResp = HandleWriteBlock(emu, subCmd);
		else if (subCmd.type == "SAVESTATE") subResp = HandleSaveState(emu, subCmd);
		else if (subCmd.type == "SAVESTATE_LABEL") subResp = HandleSaveStateLabel(emu, subCmd);
		else if (subCmd.type == "LOADSTATE") subResp = HandleLoadState(emu, subCmd);
		else if (subCmd.type == "SCREENSHOT") subResp = HandleScreenshot(emu, subCmd);
		else if (subCmd.type == "CPU") subResp = HandleGetCpuState(emu, subCmd);
		else if (subCmd.type == "STATEINSPECT") subResp = HandleStateInspector(emu, subCmd);
		else if (subCmd.type == "DISASM") subResp = HandleDisasm(emu, subCmd);
		else if (subCmd.type == "STEP") subResp = HandleStep(emu, subCmd);
		else if (subCmd.type == "FRAME") subResp = HandleRunFrame(emu, subCmd);
		else if (subCmd.type == "ROMINFO") subResp = HandleRomInfo(emu, subCmd);
		else if (subCmd.type == "BREAKPOINT") subResp = HandleBreakpoint(emu, subCmd);
		else if (subCmd.type == "LABELS") subResp = HandleLabels(emu, subCmd);
		else if (subCmd.type == "SEARCH") subResp = HandleSearch(emu, subCmd);
		else if (subCmd.type == "SNAPSHOT") subResp = HandleSnapshot(emu, subCmd);
		else if (subCmd.type == "DIFF") subResp = HandleDiff(emu, subCmd);
		else if (subCmd.type == "TRACE") subResp = HandleTrace(emu, subCmd);
		else if (subCmd.type == "LOGPOINT") subResp = HandleLogpoint(emu, subCmd);
		else if (subCmd.type == "SUBSCRIBE") subResp = HandleSubscribe(emu, subCmd);
		else if (subCmd.type == "DEBUG_LOG") subResp = HandleDebugLog(emu, subCmd);
		else {
			subResp.success = false;
			subResp.error = "Unknown command or not allowed in BATCH: " + subCmd.type;
		}

		if (!subResp.success) {
			allSuccess = false;
		}

		// Add result to output
		ss << "{\"type\":\"" << subCmd.type << "\",";
		ss << "\"success\":" << (subResp.success ? "true" : "false");
		if (!subResp.data.empty()) {
			ss << ",\"data\":" << subResp.data;
		}
		if (!subResp.error.empty()) {
			ss << ",\"error\":\"" << JsonEscape(subResp.error) << "\"";
		}
		ss << "}";
	}

	ss << "]}";

	resp.success = allSuccess;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleTrace(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto dbg = emu->GetDebugger(true);
	Debugger* debugger = dbg.GetDebugger();
	if (!debugger) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	string action;
	auto actionIt = cmd.params.find("action");
	if (actionIt != cmd.params.end()) {
		action = actionIt->second;
		std::transform(action.begin(), action.end(), action.begin(),
			[](unsigned char c) { return static_cast<char>(std::tolower(c)); });
	}

	if (!action.empty()) {
		if (action == "clear") {
			debugger->ClearExecutionTrace();
			resp.success = true;
			resp.data = "\"OK\"";
			return resp;
		}

		if (action == "status") {
			bool enabled = false;
			for (CpuType cpuType : emu->GetCpuTypes()) {
				ITraceLogger* logger = debugger->GetTraceLogger(cpuType);
				if (logger && logger->IsEnabled()) {
					enabled = true;
					break;
				}
			}

			stringstream ss;
			ss << "{\"enabled\":" << (enabled ? "true" : "false") << "}";
			resp.success = true;
			resp.data = ss.str();
			return resp;
		}

		if (action == "start" || action == "stop") {
			bool enable = action == "start";
			TraceLoggerOptions options = {};
			options.Enabled = enable;
			options.IndentCode = false;
			options.UseLabels = true;

			string format = "[Disassembly]";
			string condition;

			auto formatIt = cmd.params.find("format");
			if (formatIt != cmd.params.end()) {
				format = formatIt->second;
			}

			auto conditionIt = cmd.params.find("condition");
			if (conditionIt != cmd.params.end()) {
				condition = conditionIt->second;
			}

			auto labelsIt = cmd.params.find("labels");
			if (labelsIt != cmd.params.end()) {
				options.UseLabels = ParseBoolValue(labelsIt->second);
			}

			auto indentIt = cmd.params.find("indent");
			if (indentIt != cmd.params.end()) {
				options.IndentCode = ParseBoolValue(indentIt->second);
			}

			memset(options.Format, 0, sizeof(options.Format));
			if (!format.empty()) {
				strncpy(options.Format, format.c_str(), sizeof(options.Format) - 1);
			}

			memset(options.Condition, 0, sizeof(options.Condition));
			if (!condition.empty()) {
				strncpy(options.Condition, condition.c_str(), sizeof(options.Condition) - 1);
			}

			if (enable) {
				auto clearIt = cmd.params.find("clear");
				if (clearIt != cmd.params.end() && ParseBoolValue(clearIt->second)) {
					debugger->ClearExecutionTrace();
				}
			}

			for (CpuType cpuType : emu->GetCpuTypes()) {
				ITraceLogger* logger = debugger->GetTraceLogger(cpuType);
				if (logger) {
					logger->SetOptions(options);
				}
			}

			stringstream ss;
			ss << "{\"enabled\":" << (enable ? "true" : "false") << "}";
			resp.success = true;
			resp.data = ss.str();
			return resp;
		}

		resp.success = false;
		resp.error = "Unknown TRACE action: " + action;
		return resp;
	}

	// Get optional count parameter (default 20, max 100)
	uint32_t count = 20;
	auto countIt = cmd.params.find("count");
	if (countIt != cmd.params.end()) {
		count = std::stoul(countIt->second);
		if (count > 100) count = 100;
		if (count == 0) count = 1;
	}

	// Get optional offset parameter (default 0)
	uint32_t offset = 0;
	auto offsetIt = cmd.params.find("offset");
	if (offsetIt != cmd.params.end()) {
		offset = std::stoul(offsetIt->second);
	}

	// Allocate trace buffer
	vector<TraceRow> traceRows(count);

	// Get execution trace from debugger
	uint32_t actualCount = dbg.GetDebugger()->GetExecutionTrace(traceRows.data(), offset, count);

	// Build JSON response
	stringstream ss;
	ss << "{\"count\":" << actualCount << ",\"offset\":" << offset << ",\"entries\":[";

	for (uint32_t i = 0; i < actualCount; i++) {
		const TraceRow& row = traceRows[i];

		if (i > 0) ss << ",";

		ss << "{";
		ss << "\"pc\":\"" << FormatHex(row.ProgramCounter, 6) << "\",";
		ss << "\"cpu\":" << static_cast<int>(row.Type) << ",";

		// Format bytecode as hex string
		ss << "\"bytes\":\"";
		for (int j = 0; j < row.ByteCodeSize && j < 8; j++) {
			ss << hex << uppercase << setw(2) << setfill('0') << (int)row.ByteCode[j];
		}
		ss << dec << "\",";

		// Add disassembly output (escaped)
		string logOutput(row.LogOutput, std::min((uint32_t)row.LogSize, (uint32_t)sizeof(row.LogOutput)));
		ss << "\"disasm\":\"" << JsonEscape(logOutput) << "\"";

		ss << "}";
	}

	ss << "]}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleLogpoint(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	auto actionIt = cmd.params.find("action");
	string action = actionIt != cmd.params.end() ? actionIt->second : "list";

	auto lock = _logpointLock.AcquireSafe();

	if (action == "add") {
		auto addrIt = cmd.params.find("addr");
		if (addrIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing addr parameter";
			return resp;
		}

		SocketLogpoint lp;
		lp.id = _nextLogpointId++;
		lp.addr = std::stoul(addrIt->second, nullptr, 0);
		lp.enabled = true;
		
		auto dbg = emu->GetDebugger(true);
		lp.cpuType = dbg.GetDebugger()->GetMainCpuType();

		auto cpuIt = cmd.params.find("cpu");
		if (cpuIt != cmd.params.end()) {
			lp.cpuType = (CpuType)std::stoi(cpuIt->second);
		}

		auto exprIt = cmd.params.find("expression");
		if (exprIt != cmd.params.end()) {
			lp.expression = exprIt->second;
		}

		_logpoints.push_back(lp);
		resp.success = true;
		resp.data = "{\"id\":" + std::to_string(lp.id) + "}";
	} else if (action == "remove") {
		auto idIt = cmd.params.find("id");
		if (idIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing id parameter";
			return resp;
		}

		uint32_t id = std::stoul(idIt->second);
		auto it = std::remove_if(_logpoints.begin(), _logpoints.end(), [id](const SocketLogpoint& lp) { return lp.id == id; });
		if (it != _logpoints.end()) {
			_logpoints.erase(it, _logpoints.end());
			resp.success = true;
		} else {
			resp.success = false;
			resp.error = "Logpoint not found";
		}
	} else if (action == "list") {
		stringstream ss;
		ss << "{\"logpoints\":[";
		for (size_t i = 0; i < _logpoints.size(); i++) {
			if (i > 0) ss << ",";
			const auto& lp = _logpoints[i];
			ss << "{\"id\":" << lp.id << ",\"addr\":\"" << FormatHex(lp.addr, 6) << "\",\"cpu\":" << (int)lp.cpuType << ",\"enabled\":" << (lp.enabled ? "true" : "false") << ",\"expression\":\"" << JsonEscape(lp.expression) << "\"}";
		}
		ss << "]}";
		resp.success = true;
		resp.data = ss.str();
	} else if (action == "hits") {
		stringstream ss;
		ss << "{\"hits\":[";
		for (size_t i = 0; i < _logpointHits.size(); i++) {
			if (i > 0) ss << ",";
			const auto& hit = _logpointHits[i];
			ss << "{\"id\":" << hit.logpointId << ",\"pc\":\"" << FormatHex(hit.pc, 6) << "\",\"cpu\":" << (int)hit.cpuType << ",\"cycles\":" << hit.cycleCount << ",\"value\":\"" << JsonEscape(hit.value) << "\"}";
		}
		ss << "]}";
		resp.success = true;
		resp.data = ss.str();
	} else if (action == "clear") {
		_logpointHits.clear();
		resp.success = true;
	} else {
		resp.success = false;
		resp.error = "Unknown action: " + action;
	}

	return resp;
}

void SocketServer::CheckLogpoints(CpuType cpuType, uint32_t pc, Emulator* emu) {
	auto lock = _logpointLock.AcquireSafe();
	if (_logpoints.empty()) {
		return;
	}

	for (const auto& lp : _logpoints) {
		if (lp.enabled && lp.cpuType == cpuType && lp.addr == (int32_t)pc) {
			LogpointHit hit;
			hit.logpointId = lp.id;
			hit.pc = pc;
			hit.cpuType = cpuType;
			
			auto dbg = emu->GetDebugger(true);
			if (dbg.GetDebugger()) {
				hit.cycleCount = dbg.GetDebugger()->GetInstructionProgress(cpuType).CurrentCycle;
				if (!lp.expression.empty()) {
					EvalResultType resultType;
					hit.value = std::to_string(dbg.GetDebugger()->EvaluateExpression(lp.expression, cpuType, resultType, false));
				}
			}

			_logpointHits.push_back(hit);
			if (_logpointHits.size() > _logpointHitMaxSize) {
				_logpointHits.pop_front();
			}

			// Also broadcast as event
			stringstream ss;
			ss << "{\"id\":" << hit.logpointId << ",\"pc\":\"" << FormatHex(hit.pc, 6) << "\",\"value\":\"" << JsonEscape(hit.value) << "\"}";
			BroadcastEvent("LOGPOINT", ss.str());
		}
	}
}

SocketResponse SocketServer::HandleSubscribe(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)emu;

	// Get action (subscribe/unsubscribe/list)
	string action = "subscribe";
	auto actionIt = cmd.params.find("action");
	if (actionIt != cmd.params.end()) {
		action = actionIt->second;
	}

	// Available event types
	static const std::set<string> validEvents = {
		"breakpoint_hit", "frame_complete", "state_changed", "logpoint",
		"memory_changed", "p_changed", "all"
	};

	if (action == "subscribe") {
		// Parse events parameter - comma-separated list or JSON array in string
		std::set<string> events;
		auto eventsIt = cmd.params.find("events");
		if (eventsIt != cmd.params.end()) {
			string eventsStr = eventsIt->second;
			// Simple parsing: split by comma or parse simple array
			size_t pos = 0;
			while (pos < eventsStr.size()) {
				// Skip whitespace and delimiters
				while (pos < eventsStr.size() && (eventsStr[pos] == ' ' || eventsStr[pos] == ',' || 
				       eventsStr[pos] == '[' || eventsStr[pos] == ']' || eventsStr[pos] == '"')) {
					pos++;
				}
				if (pos >= eventsStr.size()) break;

				// Find end of event name
				size_t endPos = pos;
				while (endPos < eventsStr.size() && eventsStr[endPos] != ',' && 
				       eventsStr[endPos] != ']' && eventsStr[endPos] != '"' && eventsStr[endPos] != ' ') {
					endPos++;
				}

				if (endPos > pos) {
					string eventName = eventsStr.substr(pos, endPos - pos);
					// Normalize to lowercase
					std::transform(eventName.begin(), eventName.end(), eventName.begin(), [](unsigned char c) {
						return static_cast<char>(std::tolower(c));
					});
					if (validEvents.count(eventName) > 0) {
						events.insert(eventName);
					}
				}
				pos = endPos;
			}
		}

		// If no events specified, subscribe to all
		if (events.empty()) {
			events.insert("all");
		}

		auto lock = _eventLock.AcquireSafe();
		_eventSubscriptions[cmd.clientFd] = events;

		// Build response
		stringstream ss;
		ss << "{\"subscribed\":[";
		bool first = true;
		for (const auto& e : events) {
			if (!first) ss << ",";
			first = false;
			ss << "\"" << e << "\"";
		}
		ss << "],\"client_fd\":" << cmd.clientFd << "}";

		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "unsubscribe") {
		auto lock = _eventLock.AcquireSafe();
		_eventSubscriptions.erase(cmd.clientFd);
		resp.success = true;
		resp.data = "\"Unsubscribed\"";
	}
	else if (action == "list") {
		auto lock = _eventLock.AcquireSafe();

		stringstream ss;
		ss << "{\"available_events\":[";
		bool first = true;
		for (const auto& e : validEvents) {
			if (!first) ss << ",";
			first = false;
			ss << "\"" << e << "\"";
		}
		ss << "],\"subscriber_count\":" << _eventSubscriptions.size() << "}";

		resp.success = true;
		resp.data = ss.str();
	}
	else {
		resp.success = false;
		resp.error = "Unknown action: " + action + ". Use subscribe, unsubscribe, or list.";
	}

	return resp;
}

void SocketServer::BroadcastEvent(string eventType, string data) {
	auto lock = _eventLock.AcquireSafe();
	if (_eventSubscriptions.empty()) return;

	stringstream ss;
	ss << "{\"type\":\"EVENT\",\"event\":\"" << eventType << "\",\"data\":" << data << "}\n";
	string eventJson = ss.str();

	// Convert eventType to lowercase for matching
	string eventTypeLower = eventType;
	std::transform(eventTypeLower.begin(), eventTypeLower.end(), eventTypeLower.begin(), [](unsigned char c) {
		return static_cast<char>(std::tolower(c));
	});

	for (auto it = _eventSubscriptions.begin(); it != _eventSubscriptions.end(); ) {
		int clientFd = it->first;
		const auto& subscribedEvents = it->second;
		bool shouldSend = subscribedEvents.count("all") > 0 || subscribedEvents.count(eventTypeLower) > 0;
		if (shouldSend && !WriteAll(clientFd, eventJson)) {
			it = _eventSubscriptions.erase(it);
			continue;
		}
		++it;
	}
}

// ============================================================================
// P Register Tracking Handlers
// ============================================================================

SocketResponse SocketServer::HandlePWatch(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	string action = "start";
	auto actionIt = cmd.params.find("action");
	if (actionIt != cmd.params.end()) {
		action = actionIt->second;
	}

	if (action == "start") {
		uint32_t depth = 1000;
		auto depthIt = cmd.params.find("depth");
		if (depthIt != cmd.params.end()) {
			depth = std::stoul(depthIt->second);
			if (depth < 10) depth = 10;
			if (depth > 100000) depth = 100000;
		}

		auto lock = _pRegisterLock.AcquireSafe();
		_pRegisterLogMaxSize = depth;
		_pRegisterWatchEnabled = true;
		_pRegisterLog.clear();

		// Initialize last P register from current CPU state if available
		if (emu && emu->IsRunning()) {
			auto dbg = emu->GetDebugger(false);
			if (dbg.GetDebugger()) {
				SnesCpuState& cpu = static_cast<SnesCpuState&>(dbg.GetDebugger()->GetCpuStateRef(CpuType::Snes));
				_lastPRegister = cpu.PS;
			}
		}

		stringstream ss;
		ss << "{\"enabled\":true,\"depth\":" << depth << "}";
		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "stop") {
		auto lock = _pRegisterLock.AcquireSafe();
		_pRegisterWatchEnabled = false;

		resp.success = true;
		resp.data = "{\"enabled\":false}";
	}
	else if (action == "status") {
		auto lock = _pRegisterLock.AcquireSafe();
		stringstream ss;
		ss << "{\"enabled\":" << (_pRegisterWatchEnabled ? "true" : "false");
		ss << ",\"depth\":" << _pRegisterLogMaxSize;
		ss << ",\"count\":" << _pRegisterLog.size() << "}";
		resp.success = true;
		resp.data = ss.str();
	}
	else {
		resp.success = false;
		resp.error = "Unknown action: " + action + ". Use start, stop, or status.";
	}

	return resp;
}

SocketResponse SocketServer::HandlePLog(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)emu;

	uint32_t count = 50;
	auto countIt = cmd.params.find("count");
	if (countIt != cmd.params.end()) {
		count = std::stoul(countIt->second);
	}

	auto lock = _pRegisterLock.AcquireSafe();

	stringstream ss;
	ss << "{\"entries\":[";

	uint32_t outputCount = 0;
	// Output from most recent to oldest
	auto it = _pRegisterLog.rbegin();
	while (it != _pRegisterLog.rend() && outputCount < count) {
		if (outputCount > 0) ss << ",";

		ss << "{\"pc\":\"0x" << hex << uppercase << setw(6) << setfill('0') << it->pc << "\"";
		ss << ",\"old_p\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)it->oldP << "\"";
		ss << ",\"new_p\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)it->newP << "\"";
		ss << ",\"opcode\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)it->opcode << "\"";
		ss << dec;

		// Decode which flags changed
		uint8_t changed = it->oldP ^ it->newP;
		string flagsStr;
		if (changed & ProcFlags::Negative) flagsStr += "N";
		if (changed & ProcFlags::Overflow) flagsStr += "V";
		if (changed & ProcFlags::MemoryMode8) flagsStr += "M";
		if (changed & ProcFlags::IndexMode8) flagsStr += "X";
		if (changed & ProcFlags::Decimal) flagsStr += "D";
		if (changed & ProcFlags::IrqDisable) flagsStr += "I";
		if (changed & ProcFlags::Zero) flagsStr += "Z";
		if (changed & ProcFlags::Carry) flagsStr += "C";

		ss << ",\"flags_changed\":\"" << flagsStr << "\"";
		ss << ",\"cycle\":" << it->cycleCount;
		ss << "}";

		++it;
		++outputCount;
	}

	ss << "],\"total\":" << _pRegisterLog.size();
	ss << ",\"returned\":" << outputCount << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandlePAssert(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu || !emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	// Get address for the assertion
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

	// Get expected P value
	auto expectedIt = cmd.params.find("expected_p");
	if (expectedIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing expected_p parameter";
		return resp;
	}

	uint8_t expectedP = 0;
	string expStr = expectedIt->second;
	if (expStr.substr(0, 2) == "0x" || expStr.substr(0, 2) == "0X") {
		expectedP = static_cast<uint8_t>(std::stoul(expStr.substr(2), nullptr, 16));
	} else {
		expectedP = static_cast<uint8_t>(std::stoul(expStr, nullptr, 16));
	}

	// Get mask (optional, default 0xFF = check all bits)
	uint8_t mask = 0xFF;
	auto maskIt = cmd.params.find("mask");
	if (maskIt != cmd.params.end()) {
		string maskStr = maskIt->second;
		if (maskStr.substr(0, 2) == "0x" || maskStr.substr(0, 2) == "0X") {
			mask = static_cast<uint8_t>(std::stoul(maskStr.substr(2), nullptr, 16));
		} else {
			mask = static_cast<uint8_t>(std::stoul(maskStr, nullptr, 16));
		}
	}

	// Create a conditional breakpoint with the P assertion
	// Condition format: (P & mask) != expected
	stringstream condSS;
	condSS << "(P & 0x" << hex << uppercase << (int)mask << ") != 0x" << (int)expectedP;
	string condition = condSS.str();

	// Add the breakpoint using existing infrastructure
	auto lock = _breakpointLock.AcquireSafe();
	uint32_t newId = _nextBreakpointId++;

	SocketBreakpoint sbp;
	sbp.id = newId;
	sbp.cpuType = CpuType::Snes;
	sbp.memoryType = MemoryType::SnesMemory;
	sbp.type = static_cast<uint8_t>(BreakpointTypeFlags::Execute);
	sbp.startAddr = static_cast<int32_t>(addr);
	sbp.endAddr = static_cast<int32_t>(addr);
	sbp.enabled = true;
	sbp.condition = condition;

	_breakpoints.push_back(sbp);
	lock.Release();

	SyncBreakpoints(emu);

	stringstream ss;
	ss << "{\"id\":" << newId;
	ss << ",\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << addr << "\"";
	ss << ",\"expected_p\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)expectedP << "\"";
	ss << ",\"mask\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)mask << "\"";
	ss << ",\"condition\":\"" << JsonEscape(condition) << "\"}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

// ============================================================================
// Memory Write Attribution Handlers
// ============================================================================

SocketResponse SocketServer::HandleMemWatchWrites(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)emu;

	// Get action (add, remove, list, clear)
	string action = "add";
	auto actionIt = cmd.params.find("action");
	if (actionIt != cmd.params.end()) {
		action = actionIt->second;
	}

	if (action == "add") {
		// Get address
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

		// Get size (optional, default 1)
		uint32_t size = 1;
		auto sizeIt = cmd.params.find("size");
		if (sizeIt != cmd.params.end()) {
			size = std::stoul(sizeIt->second);
			if (size < 1) size = 1;
			if (size > 0x10000) size = 0x10000;
		}

		// Get depth (optional, default 100)
		uint32_t depth = 100;
		auto depthIt = cmd.params.find("depth");
		if (depthIt != cmd.params.end()) {
			depth = std::stoul(depthIt->second);
			if (depth < 1) depth = 1;
			if (depth > 10000) depth = 10000;
		}

		// Create watch region
		auto lock = _memoryWatchLock.AcquireSafe();
		uint32_t newId = _nextMemoryWatchId++;

		MemoryWatchRegion watch;
		watch.id = newId;
		watch.startAddr = addr;
		watch.endAddr = addr + size - 1;
		watch.maxDepth = depth;

		_memoryWatches.push_back(watch);
		_memoryWriteLog[newId] = std::deque<MemoryWriteRecord>();

		stringstream ss;
		ss << "{\"watch_id\":" << newId;
		ss << ",\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << addr << "\"";
		ss << ",\"size\":" << dec << size;
		ss << ",\"depth\":" << depth << "}";

		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "remove") {
		auto idIt = cmd.params.find("watch_id");
		if (idIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing watch_id parameter";
			return resp;
		}

		uint32_t watchId = std::stoul(idIt->second);

		auto lock = _memoryWatchLock.AcquireSafe();
		auto it = std::find_if(_memoryWatches.begin(), _memoryWatches.end(),
			[watchId](const MemoryWatchRegion& w) { return w.id == watchId; });

		if (it != _memoryWatches.end()) {
			_memoryWatches.erase(it);
			_memoryWriteLog.erase(watchId);
			resp.success = true;
			resp.data = "\"OK\"";
		} else {
			resp.success = false;
			resp.error = "Watch not found: " + std::to_string(watchId);
		}
	}
	else if (action == "list") {
		auto lock = _memoryWatchLock.AcquireSafe();

		stringstream ss;
		ss << "{\"watches\":[";
		bool first = true;
		for (const auto& w : _memoryWatches) {
			if (!first) ss << ",";
			first = false;
			ss << "{\"watch_id\":" << w.id;
			ss << ",\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << w.startAddr << "\"";
			ss << ",\"end_addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << w.endAddr << "\"";
			ss << dec << ",\"depth\":" << w.maxDepth;
			auto logIt = _memoryWriteLog.find(w.id);
			ss << ",\"log_count\":" << (logIt != _memoryWriteLog.end() ? logIt->second.size() : 0);
			ss << "}";
		}
		ss << "]}";

		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "clear") {
		auto lock = _memoryWatchLock.AcquireSafe();
		_memoryWatches.clear();
		_memoryWriteLog.clear();
		resp.success = true;
		resp.data = "\"OK\"";
	}
	else {
		resp.success = false;
		resp.error = "Unknown action: " + action;
	}

	return resp;
}

SocketResponse SocketServer::HandleMemBlame(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)emu;

	// Try to get watch_id first
	auto watchIdIt = cmd.params.find("watch_id");
	if (watchIdIt != cmd.params.end()) {
		uint32_t watchId = std::stoul(watchIdIt->second);

		auto lock = _memoryWatchLock.AcquireSafe();
		auto logIt = _memoryWriteLog.find(watchId);
		if (logIt == _memoryWriteLog.end()) {
			resp.success = false;
			resp.error = "Watch not found: " + std::to_string(watchId);
			return resp;
		}

		// Output the write log
		stringstream ss;
		ss << "{\"writes\":[";
		bool first = true;
		for (const auto& rec : logIt->second) {
			if (!first) ss << ",";
			first = false;
			ss << "{\"pc\":\"0x" << hex << uppercase << setw(6) << setfill('0') << rec.pc << "\"";
			ss << ",\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << rec.addr << "\"";
			ss << ",\"value\":\"0x" << hex << uppercase << setw(rec.size * 2) << setfill('0') << rec.value << "\"";
			ss << dec << ",\"size\":" << (int)rec.size;
			ss << ",\"sp\":\"0x" << hex << uppercase << setw(4) << setfill('0') << rec.stackPointer << "\"";
			ss << dec << ",\"cycle\":" << rec.cycleCount;
			ss << "}";
		}
		ss << "],\"count\":" << logIt->second.size() << "}";

		resp.success = true;
		resp.data = ss.str();
		return resp;
	}

	// Otherwise, try to get addr and search all watches
	auto addrIt = cmd.params.find("addr");
	if (addrIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing watch_id or addr parameter";
		return resp;
	}

	uint32_t addr = 0;
	string addrStr = addrIt->second;
	if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
		addr = std::stoul(addrStr.substr(2), nullptr, 16);
	} else {
		addr = std::stoul(addrStr, nullptr, 16);
	}

	// Find watches covering this address and collect writes
	auto lock = _memoryWatchLock.AcquireSafe();
	vector<MemoryWriteRecord> matchingWrites;

	for (const auto& w : _memoryWatches) {
		if (addr >= w.startAddr && addr <= w.endAddr) {
			auto logIt = _memoryWriteLog.find(w.id);
			if (logIt != _memoryWriteLog.end()) {
				for (const auto& rec : logIt->second) {
					if (rec.addr == addr) {
						matchingWrites.push_back(rec);
					}
				}
			}
		}
	}

	// Output matching writes
	stringstream ss;
	ss << "{\"writes\":[";
	bool first = true;
	for (const auto& rec : matchingWrites) {
		if (!first) ss << ",";
		first = false;
		ss << "{\"pc\":\"0x" << hex << uppercase << setw(6) << setfill('0') << rec.pc << "\"";
		ss << ",\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << rec.addr << "\"";
		ss << ",\"value\":\"0x" << hex << uppercase << setw(rec.size * 2) << setfill('0') << rec.value << "\"";
		ss << dec << ",\"size\":" << (int)rec.size;
		ss << ",\"sp\":\"0x" << hex << uppercase << setw(4) << setfill('0') << rec.stackPointer << "\"";
		ss << dec << ",\"cycle\":" << rec.cycleCount;
		ss << "}";
	}
	ss << "],\"count\":" << matchingWrites.size() << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

// ============================================================================
// Symbol Table Handlers
// ============================================================================

SocketResponse SocketServer::HandleSymbolsLoad(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)emu;

	auto fileIt = cmd.params.find("file");
	if (fileIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing file parameter";
		return resp;
	}

	string filePath = fileIt->second;

	// Read and parse JSON file
	std::ifstream file(filePath);
	if (!file.is_open()) {
		resp.success = false;
		resp.error = "Cannot open file: " + filePath;
		return resp;
	}

	stringstream buffer;
	buffer << file.rdbuf();
	string content = buffer.str();

	// Simple JSON parsing for symbol file
	// Expected format: {"SymbolName": {"addr": "7E0022", "size": 2, "type": "word"}, ...}
	auto lock = _symbolLock.AcquireSafe();

	// Clear existing symbols if requested
	auto clearIt = cmd.params.find("clear");
	if (clearIt != cmd.params.end() && (clearIt->second == "true" || clearIt->second == "1")) {
		_symbolTable.clear();
	}

	// Parse the JSON (basic parsing)
	size_t count = 0;
	size_t pos = 0;

	while ((pos = content.find("\"", pos)) != string::npos) {
		// Find symbol name
		size_t nameStart = pos + 1;
		size_t nameEnd = content.find("\"", nameStart);
		if (nameEnd == string::npos) break;

		string symbolName = content.substr(nameStart, nameEnd - nameStart);
		pos = nameEnd + 1;

		// Skip to the value object
		size_t objStart = content.find("{", pos);
		if (objStart == string::npos) break;

		size_t objEnd = content.find("}", objStart);
		if (objEnd == string::npos) break;

		string objContent = content.substr(objStart, objEnd - objStart + 1);
		pos = objEnd + 1;

		// Parse addr
		size_t addrPos = objContent.find("\"addr\"");
		if (addrPos == string::npos) continue;

		size_t addrValStart = objContent.find("\"", addrPos + 6);
		if (addrValStart == string::npos) continue;
		addrValStart++;

		size_t addrValEnd = objContent.find("\"", addrValStart);
		if (addrValEnd == string::npos) continue;

		string addrStr = objContent.substr(addrValStart, addrValEnd - addrValStart);
		uint32_t addr = std::stoul(addrStr, nullptr, 16);

		// Parse size (optional, default 1)
		uint8_t size = 1;
		size_t sizePos = objContent.find("\"size\"");
		if (sizePos != string::npos) {
			size_t sizeValStart = objContent.find(":", sizePos);
			if (sizeValStart != string::npos) {
				sizeValStart++;
				while (sizeValStart < objContent.size() && std::isspace(objContent[sizeValStart])) {
					sizeValStart++;
				}
				size = static_cast<uint8_t>(std::stoul(objContent.substr(sizeValStart), nullptr, 10));
			}
		}

		// Parse type (optional)
		string type = "byte";
		if (size == 2) type = "word";
		else if (size == 3) type = "long";

		size_t typePos = objContent.find("\"type\"");
		if (typePos != string::npos) {
			size_t typeValStart = objContent.find("\"", typePos + 6);
			if (typeValStart != string::npos) {
				typeValStart++;
				size_t typeValEnd = objContent.find("\"", typeValStart);
				if (typeValEnd != string::npos) {
					type = objContent.substr(typeValStart, typeValEnd - typeValStart);
				}
			}
		}

		// Add to symbol table
		SymbolEntry entry;
		entry.name = symbolName;
		entry.addr = addr;
		entry.size = size;
		entry.type = type;
		_symbolTable[symbolName] = entry;
		count++;
	}

	stringstream ss;
	ss << "{\"loaded\":" << count << ",\"total\":" << _symbolTable.size() << "}";
	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleSymbolsResolve(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)emu;

	auto symbolIt = cmd.params.find("symbol");
	if (symbolIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing symbol parameter";
		return resp;
	}

	string symbolName = symbolIt->second;

	auto lock = _symbolLock.AcquireSafe();
	auto it = _symbolTable.find(symbolName);
	if (it == _symbolTable.end()) {
		resp.success = false;
		resp.error = "Symbol not found: " + symbolName;
		return resp;
	}

	const SymbolEntry& entry = it->second;
	stringstream ss;
	ss << "{\"name\":\"" << JsonEscape(entry.name) << "\"";
	ss << ",\"addr\":\"0x" << hex << uppercase << setw(6) << setfill('0') << entry.addr << "\"";
	ss << dec << ",\"size\":" << (int)entry.size;
	ss << ",\"type\":\"" << entry.type << "\"}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

// ============================================================================
// Collision Overlay Handlers
// ============================================================================

// Static state for collision overlay
static bool _collisionOverlayEnabled = false;
static string _collisionOverlayMode = "A";  // "A", "B", or "both"
static vector<uint8_t> _collisionHighlightTiles;

// Collision overlay getters for WatchHud
bool SocketServer::IsCollisionOverlayEnabled() {
	return _collisionOverlayEnabled;
}

string SocketServer::GetCollisionOverlayMode() {
	return _collisionOverlayMode;
}

const vector<uint8_t>& SocketServer::GetCollisionHighlightTiles() {
	return _collisionHighlightTiles;
}

SocketResponse SocketServer::HandleCollisionOverlay(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)emu;

	// Check for enabled parameter
	auto enabledIt = cmd.params.find("enabled");
	if (enabledIt != cmd.params.end()) {
		_collisionOverlayEnabled = (enabledIt->second == "true" || enabledIt->second == "1");
	}

	// Check for colmap parameter
	auto colmapIt = cmd.params.find("colmap");
	if (colmapIt != cmd.params.end()) {
		string mode = colmapIt->second;
		if (mode == "A" || mode == "a") _collisionOverlayMode = "A";
		else if (mode == "B" || mode == "b") _collisionOverlayMode = "B";
		else if (mode == "both" || mode == "BOTH") _collisionOverlayMode = "both";
	}

	// Check for highlight parameter (array of tile types to highlight)
	auto highlightIt = cmd.params.find("highlight");
	if (highlightIt != cmd.params.end()) {
		_collisionHighlightTiles.clear();
		// Parse comma-separated list of hex values
		string highlights = highlightIt->second;
		// Remove brackets if present
		if (!highlights.empty() && highlights[0] == '[') {
			highlights = highlights.substr(1);
		}
		if (!highlights.empty() && highlights.back() == ']') {
			highlights.pop_back();
		}

		size_t pos = 0;
		while (pos < highlights.size()) {
			size_t end = highlights.find(',', pos);
			if (end == string::npos) end = highlights.size();
			string val = highlights.substr(pos, end - pos);
			// Trim whitespace
			size_t start = val.find_first_not_of(" \t");
			size_t last = val.find_last_not_of(" \t");
			if (start != string::npos && last != string::npos) {
				val = val.substr(start, last - start + 1);
			}
			if (!val.empty()) {
				uint8_t tileType = 0;
				if (val.substr(0, 2) == "0x" || val.substr(0, 2) == "0X") {
					tileType = static_cast<uint8_t>(std::stoul(val.substr(2), nullptr, 16));
				} else {
					tileType = static_cast<uint8_t>(std::stoul(val, nullptr, 16));
				}
				_collisionHighlightTiles.push_back(tileType);
			}
			pos = end + 1;
		}
	}

	stringstream ss;
	ss << "{\"enabled\":" << (_collisionOverlayEnabled ? "true" : "false");
	ss << ",\"colmap\":\"" << _collisionOverlayMode << "\"";
	ss << ",\"highlight\":[";
	for (size_t i = 0; i < _collisionHighlightTiles.size(); i++) {
		if (i > 0) ss << ",";
		ss << "\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)_collisionHighlightTiles[i] << "\"";
	}
	ss << "]}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleCollisionDump(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu || !emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	// Get which collision map to dump
	string colmap = "A";
	auto colmapIt = cmd.params.find("colmap");
	if (colmapIt != cmd.params.end()) {
		colmap = colmapIt->second;
		if (colmap != "A" && colmap != "a" && colmap != "B" && colmap != "b") {
			colmap = "A";
		}
	}

	// ALTTP collision map addresses:
	// COLMAPA: $7F2000 (16KB, 64x64 tiles)
	// COLMAPB: $7F6000 (16KB, 64x64 tiles)
	uint32_t baseAddr = (colmap == "B" || colmap == "b") ? 0x7F6000 : 0x7F2000;

	auto dbg = emu->GetDebugger(false);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	Debugger* debugger = dbg.GetDebugger();
	MemoryDumper* dumper = debugger->GetMemoryDumper();

	// Read collision map (64x64 = 4096 tiles)
	const int mapSize = 64;

	stringstream ss;
	ss << "{\"colmap\":\"" << colmap << "\",\"width\":" << mapSize << ",\"height\":" << mapSize;
	ss << ",\"data\":[";

	for (int y = 0; y < mapSize; y++) {
		if (y > 0) ss << ",";
		ss << "[";
		for (int x = 0; x < mapSize; x++) {
			if (x > 0) ss << ",";
			uint32_t addr = baseAddr + (y * mapSize) + x;
			uint8_t value = dumper->GetMemoryValue(MemoryType::SnesMemory, addr);
			ss << (int)value;
		}
		ss << "]";
	}

	ss << "]}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

// ============================================================================
// Base64 Encoding Helper
// ============================================================================

static const char* Base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static string Base64Encode(const vector<uint8_t>& data) {
	string result;
	result.reserve((data.size() * 4 / 3) + 4);

	size_t i = 0;
	while (i + 2 < data.size()) {
		uint32_t n = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
		result.push_back(Base64Chars[(n >> 18) & 0x3F]);
		result.push_back(Base64Chars[(n >> 12) & 0x3F]);
		result.push_back(Base64Chars[(n >> 6) & 0x3F]);
		result.push_back(Base64Chars[n & 0x3F]);
		i += 3;
	}

	if (i + 1 == data.size()) {
		uint32_t n = data[i] << 16;
		result.push_back(Base64Chars[(n >> 18) & 0x3F]);
		result.push_back(Base64Chars[(n >> 12) & 0x3F]);
		result.push_back('=');
		result.push_back('=');
	} else if (i + 2 == data.size()) {
		uint32_t n = (data[i] << 16) | (data[i + 1] << 8);
		result.push_back(Base64Chars[(n >> 18) & 0x3F]);
		result.push_back(Base64Chars[(n >> 12) & 0x3F]);
		result.push_back(Base64Chars[(n >> 6) & 0x3F]);
		result.push_back('=');
	}

	return result;
}

static string Base64Decode(const string& encoded) {
	if (encoded.empty()) return "";

	static const int decodingTable[256] = {
		-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
		-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
		-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
		52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
		-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
		15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
		-1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
		41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1
	};

	string result;
	result.reserve(encoded.size() * 3 / 4);

	int val = 0, valb = -8;
	for (uint8_t c : encoded) {
		if (c == '=') break;
		if (decodingTable[c] == -1) continue;
		val = (val << 6) + decodingTable[c];
		valb += 6;
		if (valb >= 0) {
			result.push_back(char((val >> valb) & 0xFF));
			valb -= 8;
		}
	}
	return result;
}

// ============================================================================
// Performance Handlers
// ============================================================================

SocketResponse SocketServer::HandleReadBlockBinary(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	auto addrIt = cmd.params.find("addr");
	auto lenIt = cmd.params.find("len");
	if (lenIt == cmd.params.end()) {
		lenIt = cmd.params.find("length");
	}
	if (lenIt == cmd.params.end()) {
		lenIt = cmd.params.find("size");
	}

	if (addrIt == cmd.params.end() || lenIt == cmd.params.end()) {
		resp.success = false;
		resp.error = "Missing addr or len/size parameter";
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
	if (len > 0x100000) len = 0x100000; // Limit to 1MB for binary transfers

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

	// Read memory into buffer
	vector<uint8_t> data(len);
	for (uint32_t i = 0; i < len; i++) {
		data[i] = dumper->GetMemoryValue(memType, addr + i);
	}

	// Encode as base64
	string base64 = Base64Encode(data);

	stringstream ss;
	ss << "{\"bytes\":\"" << base64 << "\",\"size\":" << len << ",\"addr\":\"0x";
	ss << hex << uppercase << setw(6) << setfill('0') << addr << "\"}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

// ============================================================================
// API Discovery Handlers
// ============================================================================

SocketResponse SocketServer::HandleHelp(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)emu;

	auto commandIt = cmd.params.find("command");

	if (commandIt != cmd.params.end()) {
		// Help for specific command
		string cmdName = commandIt->second;
		std::transform(cmdName.begin(), cmdName.end(), cmdName.begin(), [](unsigned char c) {
			return static_cast<char>(std::toupper(c));
		});

		struct CommandHelp {
			const char* name;
			const char* description;
			const char* params;
			const char* example;
		};

		static const vector<CommandHelp> commandHelps = {
			{"PING", "Check connectivity", "", "{\"type\":\"PING\"}"},
			{"STATE", "Get emulation state (running, paused, frame, fps)", "", "{\"type\":\"STATE\"}"},
			{"HEALTH", "Detailed health check with PC and disasm sample", "", "{\"type\":\"HEALTH\"}"},
			{"PAUSE", "Pause emulation", "", "{\"type\":\"PAUSE\"}"},
			{"RESUME", "Resume emulation", "", "{\"type\":\"RESUME\"}"},
			{"RESET", "Reset the emulator", "", "{\"type\":\"RESET\"}"},
			{"FRAME", "Run one frame", "", "{\"type\":\"FRAME\"}"},
			{"STEP", "Step one instruction", "count (optional)", "{\"type\":\"STEP\",\"count\":\"10\"}"},
			{"READ", "Read 1 byte from memory", "addr, memtype (optional)", "{\"type\":\"READ\",\"addr\":\"0x7E0022\"}"},
			{"READ16", "Read 2 bytes (little-endian word)", "addr, memtype (optional)", "{\"type\":\"READ16\",\"addr\":\"0x7E0022\"}"},
			{"READBLOCK", "Read N bytes as hex string", "addr, len, memtype (optional)", "{\"type\":\"READBLOCK\",\"addr\":\"0x7E0000\",\"len\":\"256\"}"},
			{"READBLOCK_BINARY", "Read N bytes as base64 (50% smaller)", "addr, len/size, memtype (optional)", "{\"type\":\"READBLOCK_BINARY\",\"addr\":\"0x7E0000\",\"size\":\"8192\"}"},
			{"WRITE", "Write 1 byte to memory", "addr, value, memtype (optional)", "{\"type\":\"WRITE\",\"addr\":\"0x7E0022\",\"value\":\"0x42\"}"},
			{"WRITE16", "Write 2 bytes (little-endian word)", "addr, value, memtype (optional)", "{\"type\":\"WRITE16\",\"addr\":\"0x7E0022\",\"value\":\"0x1234\"}"},
			{"WRITEBLOCK", "Write N bytes from hex string", "addr, hex, memtype (optional)", "{\"type\":\"WRITEBLOCK\",\"addr\":\"0x7E0000\",\"hex\":\"A9008D\"}"},
			{"CPU", "Get compact CPU register state", "", "{\"type\":\"CPU\"}"},
			{"DISASM", "Disassemble at address", "addr, count (optional), cputype (optional)", "{\"type\":\"DISASM\",\"addr\":\"0x008000\",\"count\":\"10\"}"},
			{"BREAKPOINT", "Manage breakpoints", "action (add/list/remove/enable/disable/clear), addr, bptype, condition", "{\"type\":\"BREAKPOINT\",\"action\":\"add\",\"addr\":\"0x008000\",\"bptype\":\"exec\"}"},
			{"TRACE", "Get or control execution trace log", "action (start/stop/status/clear) or count/offset; format/condition/labels/indent", "{\"type\":\"TRACE\",\"action\":\"start\",\"clear\":\"true\"}"},
			{"BATCH", "Execute multiple commands at once", "commands (JSON array as string)", "{\"type\":\"BATCH\",\"commands\":\"[{\\\"type\\\":\\\"PING\\\"}]\"}"},
			{"SCREENSHOT", "Capture screen as base64 PNG", "", "{\"type\":\"SCREENSHOT\"}"},
			{"SAVESTATE", "Save state to slot or file", "slot or path, label (optional), pause (optional), allow_external (optional)", "{\"type\":\"SAVESTATE\",\"slot\":\"1\",\"label\":\"Boss room\",\"pause\":\"true\"}"},
			{"SAVESTATE_LABEL", "Get/set save state labels", "action (get/set/clear), slot or path, label (set only)", "{\"type\":\"SAVESTATE_LABEL\",\"action\":\"set\",\"slot\":\"1\",\"label\":\"Boss room\"}"},
			{"LOADSTATE", "Load state from slot or file", "slot or path, pause (optional), allow_external (optional)", "{\"type\":\"LOADSTATE\",\"slot\":\"1\",\"pause\":\"true\"}"},
			{"SNAPSHOT", "Create memory snapshot for diff", "name, memtype (optional)", "{\"type\":\"SNAPSHOT\",\"name\":\"before\"}"},
			{"DIFF", "Compare current memory to snapshot", "snapshot", "{\"type\":\"DIFF\",\"snapshot\":\"before\"}"},
			{"SEARCH", "Search memory for byte pattern", "pattern, memtype, start, end", "{\"type\":\"SEARCH\",\"pattern\":\"A9 00 8D\"}"},
			{"LABELS", "Manage debug labels", "action (set/get/lookup/clear)", "{\"type\":\"LABELS\",\"action\":\"lookup\",\"addr\":\"0x008000\"}"},
			{"P_WATCH", "Enable/disable P register change tracking", "action (start/stop/status), depth", "{\"type\":\"P_WATCH\",\"action\":\"start\",\"depth\":\"500\"}"},
			{"P_LOG", "Get recent P register changes", "count", "{\"type\":\"P_LOG\",\"count\":\"50\"}"},
			{"P_ASSERT", "Break when P doesn't match expected value", "addr, expected_p, mask", "{\"type\":\"P_ASSERT\",\"addr\":\"0x008000\",\"expected_p\":\"0x30\"}"},
			{"MEM_WATCH_WRITES", "Track writes to memory regions", "action (add/remove/list/clear), addr, size, depth", "{\"type\":\"MEM_WATCH_WRITES\",\"action\":\"add\",\"addr\":\"0x7E0022\",\"size\":\"2\"}"},
			{"MEM_BLAME", "Get write attribution for watched address", "watch_id or addr", "{\"type\":\"MEM_BLAME\",\"addr\":\"0x7E0022\"}"},
			{"DEBUG_LOG", "Get emulator debug log lines", "count (optional), contains (optional)", "{\"type\":\"DEBUG_LOG\",\"count\":\"50\",\"contains\":\"[SP]\"}"},
			{"SYMBOLS_LOAD", "Load symbol table from JSON file", "file, clear", "{\"type\":\"SYMBOLS_LOAD\",\"file\":\"/path/to/symbols.json\"}"},
			{"SYMBOLS_RESOLVE", "Resolve symbol name to address", "symbol", "{\"type\":\"SYMBOLS_RESOLVE\",\"symbol\":\"Link_X_Position\"}"},
			{"COLLISION_OVERLAY", "Toggle ALTTP collision visualization", "action (enable/disable/status), colmap, highlight", "{\"type\":\"COLLISION_OVERLAY\",\"action\":\"enable\",\"colmap\":\"A\"}"},
			{"COLLISION_DUMP", "Export ALTTP collision map data", "colmap (A or B)", "{\"type\":\"COLLISION_DUMP\",\"colmap\":\"A\"}"},
			{"ROMINFO", "Get ROM information", "", "{\"type\":\"ROMINFO\"}"},
			{"SPEED", "Set emulation speed", "speed (1.0 = normal)", "{\"type\":\"SPEED\",\"speed\":\"2.0\"}"},
			{"REWIND", "Rewind emulation", "frames", "{\"type\":\"REWIND\",\"frames\":\"60\"}"},
			{"CHEAT", "Manage cheat codes", "action (add/list/clear), code", "{\"type\":\"CHEAT\",\"action\":\"add\",\"code\":\"7E0022:99\"}"},
			{"INPUT", "Set input override", "buttons", "{\"type\":\"INPUT\",\"buttons\":\"right\"}"},
			{"STATEINSPECT", "Get detailed CPU/PPU/watch state", "includeGameState (optional)", "{\"type\":\"STATEINSPECT\",\"includeGameState\":\"true\"}"},
			{"LOGPOINT", "Manage logpoints (non-halting breakpoints)", "action (add/list/remove/clear/get)", "{\"type\":\"LOGPOINT\",\"action\":\"add\",\"addr\":\"0x008000\",\"expr\":\"A\"}"},
			{"SUBSCRIBE", "Subscribe to event notifications", "events (array)", "{\"type\":\"SUBSCRIBE\",\"events\":\"[\\\"breakpoint_hit\\\"]\"}"},
			{"LOADSCRIPT", "Load Lua script", "path or content", "{\"type\":\"LOADSCRIPT\",\"path\":\"/path/to/script.lua\"}"},
			{"HELP", "Get API help", "command (optional)", "{\"type\":\"HELP\",\"command\":\"BREAKPOINT\"}"},
		};

		for (const auto& help : commandHelps) {
			if (cmdName == help.name) {
				stringstream ss;
				ss << "{\"command\":\"" << help.name << "\"";
				ss << ",\"description\":\"" << JsonEscape(help.description) << "\"";
				ss << ",\"params\":\"" << JsonEscape(help.params) << "\"";
				ss << ",\"example\":" << help.example << "}";
				resp.success = true;
				resp.data = ss.str();
				return resp;
			}
		}

		resp.success = false;
		resp.error = "Unknown command: " + cmdName;
		return resp;
	}

	// List all commands
	static const vector<string> commands = {
		"PING", "STATE", "HEALTH", "PAUSE", "RESUME", "RESET", "FRAME", "STEP",
		"READ", "READ16", "READBLOCK", "READBLOCK_BINARY", "WRITE", "WRITE16", "WRITEBLOCK",
		"CPU", "DISASM", "BREAKPOINT", "TRACE", "BATCH",
		"SCREENSHOT", "SAVESTATE", "SAVESTATE_LABEL", "LOADSTATE",
		"SNAPSHOT", "DIFF", "SEARCH", "LABELS",
		"P_WATCH", "P_LOG", "P_ASSERT",
		"DEBUG_LOG",
		"MEM_WATCH_WRITES", "MEM_BLAME",
		"SYMBOLS_LOAD", "SYMBOLS_RESOLVE",
		"COLLISION_OVERLAY", "COLLISION_DUMP",
		"ROMINFO", "SPEED", "REWIND", "CHEAT", "INPUT",
		"STATEINSPECT", "LOGPOINT", "SUBSCRIBE", "LOADSCRIPT", "HELP",
		"GAMESTATE", "SPRITES"
	};

	stringstream ss;
	ss << "{\"version\":\"1.0.0\",\"commands\":[";
	for (size_t i = 0; i < commands.size(); i++) {
		if (i > 0) ss << ",";
		ss << "\"" << commands[i] << "\"";
	}
	ss << "],\"count\":" << commands.size();
	ss << ",\"usage\":\"Use HELP with command parameter for details, e.g. {\\\"type\\\":\\\"HELP\\\",\\\"command\\\":\\\"BREAKPOINT\\\"}\"}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

// ============================================================================
// ALTTP Game State Handlers
// ============================================================================

// ALTTP Memory Map Constants
namespace ALTTP {
	// Link Position/State
	constexpr uint32_t LINK_X_LOW = 0x7E0022;      // Link X position (low byte)
	constexpr uint32_t LINK_X_HIGH = 0x7E0023;     // Link X position (high byte)
	constexpr uint32_t LINK_Y_LOW = 0x7E0020;      // Link Y position (low byte)
	constexpr uint32_t LINK_Y_HIGH = 0x7E0021;     // Link Y position (high byte)
	constexpr uint32_t LINK_LAYER = 0x7E00EE;      // Link layer (BG1/BG2)
	constexpr uint32_t LINK_DIRECTION = 0x7E002F;  // Link facing direction
	constexpr uint32_t LINK_STATE = 0x7E005D;      // Link action state
	constexpr uint32_t LINK_POSE = 0x7E0046;       // Link pose/animation
	
	// Health/Items
	constexpr uint32_t HEALTH_CURRENT = 0x7EF36D;  // Current health
	constexpr uint32_t HEALTH_MAX = 0x7EF36C;      // Max health
	constexpr uint32_t MAGIC_CURRENT = 0x7EF36E;   // Current magic
	constexpr uint32_t RUPEES_LOW = 0x7EF360;      // Rupees low byte
	constexpr uint32_t RUPEES_HIGH = 0x7EF361;     // Rupees high byte
	constexpr uint32_t BOMBS = 0x7EF343;           // Bomb count
	constexpr uint32_t ARROWS = 0x7EF377;          // Arrow count
	
	// Game State
	constexpr uint32_t GAME_MODE = 0x7E0010;       // Main game mode
	constexpr uint32_t GAME_SUBMODE = 0x7E0011;    // Game submode
	constexpr uint32_t MODULE = 0x7E0010;          // Module index
	constexpr uint32_t SUBMODULE = 0x7E0011;       // Submodule index
	
	// Room/Area
	constexpr uint32_t ROOM_ID_LOW = 0x7E00A0;     // Current room ID (low)
	constexpr uint32_t ROOM_ID_HIGH = 0x7E00A1;    // Current room ID (high)
	constexpr uint32_t OVERWORLD_AREA = 0x7E008A;  // Overworld area ID
	constexpr uint32_t INDOORS = 0x7E001B;         // Indoor flag
	constexpr uint32_t DUNGEON_ID = 0x7E040C;      // Current dungeon ID
	
	// Sprites
	constexpr uint32_t SPRITE_TABLE = 0x7E0D00;    // Sprite table start
	constexpr uint32_t SPRITE_COUNT = 16;          // Max sprites
	constexpr uint32_t SPRITE_TYPE = 0x7E0E20;     // Sprite type table
	constexpr uint32_t SPRITE_STATE = 0x7E0DD0;    // Sprite state table
	constexpr uint32_t SPRITE_X_LOW = 0x7E0D10;    // Sprite X low
	constexpr uint32_t SPRITE_X_HIGH = 0x7E0D30;   // Sprite X high
	constexpr uint32_t SPRITE_Y_LOW = 0x7E0D00;    // Sprite Y low
	constexpr uint32_t SPRITE_Y_HIGH = 0x7E0D20;   // Sprite Y high
	constexpr uint32_t SPRITE_HEALTH = 0x7E0E50;   // Sprite health
	constexpr uint32_t SPRITE_SUBTYPE = 0x7E0E40;  // Sprite subtype
}

SocketResponse SocketServer::HandleGameState(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	(void)cmd;

	if (!emu || !emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto dbg = emu->GetDebugger(false);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	MemoryDumper* dumper = dbg.GetDebugger()->GetMemoryDumper();

	// Helper to read a byte
	auto readByte = [dumper](uint32_t addr) -> uint8_t {
		return dumper->GetMemoryValue(MemoryType::SnesMemory, addr);
	};

	// Helper to read a word (little-endian)
	auto readWord = [dumper](uint32_t addrLow) -> uint16_t {
		uint8_t lo = dumper->GetMemoryValue(MemoryType::SnesMemory, addrLow);
		uint8_t hi = dumper->GetMemoryValue(MemoryType::SnesMemory, addrLow + 1);
		return lo | (hi << 8);
	};

	// Read Link's state
	uint16_t linkX = readWord(ALTTP::LINK_X_LOW);
	uint16_t linkY = readWord(ALTTP::LINK_Y_LOW);
	uint8_t linkLayer = readByte(ALTTP::LINK_LAYER);
	uint8_t linkDirection = readByte(ALTTP::LINK_DIRECTION);
	uint8_t linkState = readByte(ALTTP::LINK_STATE);
	uint8_t linkPose = readByte(ALTTP::LINK_POSE);

	// Read health/items
	uint8_t healthCurrent = readByte(ALTTP::HEALTH_CURRENT);
	uint8_t healthMax = readByte(ALTTP::HEALTH_MAX);
	uint8_t magic = readByte(ALTTP::MAGIC_CURRENT);
	uint16_t rupees = readWord(ALTTP::RUPEES_LOW);
	uint8_t bombs = readByte(ALTTP::BOMBS);
	uint8_t arrows = readByte(ALTTP::ARROWS);

	// Read game state
	uint8_t gameMode = readByte(ALTTP::GAME_MODE);
	uint8_t gameSubmode = readByte(ALTTP::GAME_SUBMODE);
	uint16_t roomId = readWord(ALTTP::ROOM_ID_LOW);
	uint8_t overworldArea = readByte(ALTTP::OVERWORLD_AREA);
	uint8_t indoors = readByte(ALTTP::INDOORS);
	uint8_t dungeonId = readByte(ALTTP::DUNGEON_ID);

	// Direction names
	static const char* directions[] = {"up", "down", "left", "right"};
	const char* dirName = (linkDirection < 4) ? directions[linkDirection] : "unknown";

	// Build response
	stringstream ss;
	ss << "{";
	
	// Link section
	ss << "\"link\":{";
	ss << "\"x\":" << linkX;
	ss << ",\"y\":" << linkY;
	ss << ",\"layer\":" << (int)linkLayer;
	ss << ",\"direction\":\"" << dirName << "\"";
	ss << ",\"state\":" << (int)linkState;
	ss << ",\"pose\":" << (int)linkPose;
	ss << "}";

	// Health section
	ss << ",\"health\":{";
	ss << "\"current\":" << (int)healthCurrent;
	ss << ",\"max\":" << (int)healthMax;
	ss << ",\"hearts\":" << fixed << setprecision(1) << (healthCurrent / 8.0);
	ss << ",\"max_hearts\":" << fixed << setprecision(1) << (healthMax / 8.0);
	ss << "}";

	// Items section
	ss << ",\"items\":{";
	ss << "\"magic\":" << (int)magic;
	ss << ",\"rupees\":" << rupees;
	ss << ",\"bombs\":" << (int)bombs;
	ss << ",\"arrows\":" << (int)arrows;
	ss << "}";

	// Game state section
	ss << ",\"game\":{";
	ss << "\"mode\":" << (int)gameMode;
	ss << ",\"submode\":" << (int)gameSubmode;
	ss << ",\"indoors\":" << (indoors ? "true" : "false");
	if (indoors) {
		ss << ",\"room_id\":\"0x" << hex << uppercase << setw(4) << setfill('0') << roomId << "\"";
		ss << dec << ",\"dungeon_id\":" << (int)dungeonId;
	} else {
		ss << ",\"overworld_area\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)overworldArea << "\"";
	}
	ss << dec << "}";

	ss << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleSprites(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu || !emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	auto dbg = emu->GetDebugger(false);
	if (!dbg.GetDebugger()) {
		resp.success = false;
		resp.error = "Debugger not available";
		return resp;
	}

	MemoryDumper* dumper = dbg.GetDebugger()->GetMemoryDumper();

	// Parse optional slot filter
	int slotFilter = -1;
	auto slotIt = cmd.params.find("slot");
	if (slotIt != cmd.params.end()) {
		slotFilter = std::stoi(slotIt->second);
	}

	// Parse active-only filter
	bool activeOnly = true;
	auto allIt = cmd.params.find("all");
	if (allIt != cmd.params.end() && (allIt->second == "true" || allIt->second == "1")) {
		activeOnly = false;
	}

	// Helper to read a byte
	auto readByte = [dumper](uint32_t addr) -> uint8_t {
		return dumper->GetMemoryValue(MemoryType::SnesMemory, addr);
	};

	// Read sprites
	vector<std::tuple<int, uint8_t, uint8_t, uint16_t, uint16_t, uint8_t, uint8_t>> sprites;

	for (int slot = 0; slot < (int)ALTTP::SPRITE_COUNT; slot++) {
		if (slotFilter >= 0 && slot != slotFilter) continue;

		uint8_t spriteType = readByte(ALTTP::SPRITE_TYPE + slot);
		uint8_t spriteState = readByte(ALTTP::SPRITE_STATE + slot);

		// Skip inactive sprites if activeOnly
		if (activeOnly && spriteState == 0) continue;

		uint8_t xLow = readByte(ALTTP::SPRITE_X_LOW + slot);
		uint8_t xHigh = readByte(ALTTP::SPRITE_X_HIGH + slot);
		uint8_t yLow = readByte(ALTTP::SPRITE_Y_LOW + slot);
		uint8_t yHigh = readByte(ALTTP::SPRITE_Y_HIGH + slot);
		uint16_t x = xLow | (xHigh << 8);
		uint16_t y = yLow | (yHigh << 8);
		uint8_t health = readByte(ALTTP::SPRITE_HEALTH + slot);
		uint8_t subtype = readByte(ALTTP::SPRITE_SUBTYPE + slot);

		sprites.emplace_back(slot, spriteType, spriteState, x, y, health, subtype);
	}

	// Build response
	stringstream ss;
	ss << "{\"count\":" << sprites.size() << ",\"sprites\":[";

	bool first = true;
	for (const auto& [slot, type, state, x, y, health, subtype] : sprites) {
		if (!first) ss << ",";
		first = false;

		ss << "{\"slot\":" << slot;
		ss << ",\"type\":\"0x" << hex << uppercase << setw(2) << setfill('0') << (int)type << "\"";
		ss << dec << ",\"state\":" << (int)state;
		ss << ",\"x\":" << x;
		ss << ",\"y\":" << y;
		ss << ",\"health\":" << (int)health;
		ss << ",\"subtype\":" << (int)subtype;
		ss << "}";
	}

	ss << "]}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleCallstack(Emulator* emu, const SocketCommand& cmd) {
    SocketResponse resp;
    auto dbg = emu->GetDebugger(true);
    if (!dbg.GetDebugger()) {
        resp.success = false;
        resp.error = "Debugger not available";
        resp.errorCode = SocketErrorCode::DebuggerNotAvailable;
        return resp;
    }

    CpuType cpuType = emu->GetCpuTypes()[0];
    auto callstackManager = dbg.GetDebugger()->GetCallstackManager(cpuType);
    if (!callstackManager) {
        resp.success = false;
        resp.error = "Callstack manager not available";
        resp.errorCode = SocketErrorCode::DebuggerNotAvailable;
        return resp;
    }
    
    uint32_t size = 512;
    vector<StackFrameInfo> frames(size);
    callstackManager->GetCallstack(frames.data(), size);
    if (size == 0) {
        resp.success = true;
        resp.data = "[]";
        return resp;
    }

    stringstream ss;
    ss << "[";
    for (uint32_t i = 0; i < size; i++) {
        if (i > 0) ss << ",";
        ss << "{\"source\":\"" << FormatHex(frames[i].AbsSource.Address, 6) << "\",";
        ss << "\"target\":\"" << FormatHex(frames[i].AbsTarget.Address, 6) << "\",";
        ss << "\"return\":\"" << FormatHex(frames[i].AbsReturn.Address, 6) << "\",";
        ss << "\"flags\":" << (int)frames[i].Flags << "}";
    }
    ss << "]";

    resp.success = true;
    resp.data = ss.str();
    return resp;
}

SocketResponse SocketServer::HandleOsd(Emulator* emu, const SocketCommand& cmd) {
    SocketResponse resp;
    auto textIt = cmd.params.find("text");
    if (textIt == cmd.params.end()) {
        resp.success = false;
        resp.error = "Missing text parameter";
        resp.errorCode = SocketErrorCode::MissingParameter;
        return resp;
    }

    if (emu->GetVideoRenderer()) {
        emu->GetVideoRenderer()->SetWatchHudText(textIt->second);
        resp.success = true;
        resp.data = "\"OK\"";
    } else {
        resp.success = false;
        resp.error = "Video renderer not available";
        resp.errorCode = SocketErrorCode::EmulatorNotRunning;
    }
    return resp;
}

// Agent discovery handlers
SocketResponse SocketServer::HandleCapabilities(Emulator* emu, const SocketCommand& cmd) {
    SocketResponse resp;
    
    size_t handlerCount = 0;
    if(emu) {
        SocketServer* server = emu->GetSocketServer();
        if(server) {
            auto lock = server->_lock.AcquireSafe();
            handlerCount = server->_handlers.size();
        }
    }
    
    stringstream ss;
    ss << "{";
    ss << "\"version\":\"1.1.0\",";
    ss << "\"commands\":" << handlerCount << ",";
    ss << "\"features\":[\"error_codes\",\"validation\",\"yaze_sync\",\"p_watch\",\"mem_blame\",\"batch\",\"gamestate\",\"sprites\",\"script_running\",\"savestate_labels\",\"savestate_slots\"]";
    ss << "}";
    
    resp.success = true;
    resp.data = ss.str();
    return resp;
}

SocketResponse SocketServer::HandleAgentRegister(Emulator* emu, const SocketCommand& cmd) {
    SocketResponse resp;
    
    auto idIt = cmd.params.find("agentId");
    string agentId = idIt != cmd.params.end() ? idIt->second : "unknown";
    
    auto nameIt = cmd.params.find("agentName");
    string agentName = nameIt != cmd.params.end() ? nameIt->second : "Unknown Agent";
    
    auto versionIt = cmd.params.find("version");
    string version = versionIt != cmd.params.end() ? versionIt->second : "1.0.0";
    
    // Register agent
    {
        auto lock = _agentLock.AcquireSafe();
        AgentInfo info;
        info.agentId = agentId;
        info.agentName = agentName;
        info.version = version;
        uint64_t nowEpoch = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());
        info.connectedAt = nowEpoch;
        info.lastSeen = nowEpoch;
        _registeredAgents[cmd.clientFd] = info;
    }
    
    stringstream ss;
    ss << "{\"registered\":true,\"agentId\":\"" << JsonEscape(agentId) << "\"}";
    
    resp.success = true;
    resp.data = ss.str();
    return resp;
}

SocketResponse SocketServer::HandleMetrics(Emulator* emu, const SocketCommand& cmd) {
    SocketResponse resp;
    
    size_t totalCommands = 0;
    size_t errorCount = 0;
    uint64_t totalLatency = 0;
    size_t agentCount = 0;
    size_t subscriptionCount = 0;
    
    {
        auto lock = _historyLock.AcquireSafe();
        totalCommands = _commandHistory.size();
        for (const auto& entry : _commandHistory) {
            if (entry.errorCode != SocketErrorCode::None) {
                errorCount++;
            }
            totalLatency += entry.latencyUs;
        }
    }
    
    {
        auto lock = _agentLock.AcquireSafe();
        agentCount = _registeredAgents.size();
    }
    
    {
        auto lock = _eventLock.AcquireSafe();
        subscriptionCount = _eventSubscriptions.size();
    }
    
    double avgLatency = totalCommands > 0 ? (double)totalLatency / totalCommands : 0;
    double errorRate = totalCommands > 0 ? (double)errorCount / totalCommands : 0;
    
    stringstream ss;
    ss << "{";
    ss << "\"totalCommands\":" << totalCommands << ",";
    ss << "\"avgLatencyUs\":" << fixed << setprecision(2) << avgLatency << ",";
    ss << "\"errorCount\":" << errorCount << ",";
    ss << "\"errorRate\":" << fixed << setprecision(4) << errorRate << ",";
    ss << "\"registeredAgents\":" << agentCount << ",";
    ss << "\"activeSubscriptions\":" << subscriptionCount;
    ss << "}";
    
    resp.success = true;
    resp.data = ss.str();
    return resp;
}

SocketResponse SocketServer::HandleCommandHistory(Emulator* emu, const SocketCommand& cmd) {
    SocketResponse resp;
    
    auto countIt = cmd.params.find("count");
    size_t count = 20;
    if (countIt != cmd.params.end()) {
        try {
            count = std::stoul(countIt->second);
        } catch (...) {}
    }
    
    stringstream ss;
    ss << "[";
    
    {
        auto lock = _historyLock.AcquireSafe();
        size_t start = _commandHistory.size() > count ? _commandHistory.size() - count : 0;
        bool first = true;
        for (size_t i = start; i < _commandHistory.size(); i++) {
            if (!first) ss << ",";
            first = false;
            
            const auto& entry = _commandHistory[i];
            ss << "{";
            ss << "\"command\":\"" << JsonEscape(entry.command) << "\",";
            ss << "\"timestamp\":\"" << entry.timestamp << "\",";
            ss << "\"errorCode\":" << static_cast<int>(entry.errorCode) << ",";
            ss << "\"latencyUs\":" << entry.latencyUs;
            ss << "}";
        }
    }
    
    ss << "]";
    
    resp.success = true;
    resp.data = ss.str();
    return resp;
}

SocketResponse SocketServer::HandleDebugLog(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu || !emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		resp.errorCode = SocketErrorCode::EmulatorNotRunning;
		return resp;
	}

	auto dbgReq = emu->GetDebugger(true);
	Debugger* dbg = dbgReq.GetDebugger();
	if (!dbg) {
		resp.success = false;
		resp.error = "Debugger not available";
		resp.errorCode = SocketErrorCode::DebuggerNotAvailable;
		return resp;
	}

	size_t count = 200;
	auto countIt = cmd.params.find("count");
	if (countIt != cmd.params.end()) {
		try {
			count = std::stoul(countIt->second);
		} catch (...) {}
	}

	string contains;
	auto containsIt = cmd.params.find("contains");
	if (containsIt != cmd.params.end()) {
		contains = containsIt->second;
	}

	string log = dbg->GetLog();
	vector<string> lines;
	lines.reserve(256);

	size_t start = 0;
	while (start <= log.size()) {
		size_t end = log.find('\n', start);
		if (end == string::npos) {
			end = log.size();
		}
		string line = log.substr(start, end - start);
		if (!line.empty()) {
			if (contains.empty() || line.find(contains) != string::npos) {
				lines.push_back(line);
			}
		}
		if (end >= log.size()) break;
		start = end + 1;
	}

	size_t total = lines.size();
	size_t begin = total > count ? total - count : 0;

	stringstream ss;
	ss << "{\"lines\":[";
	for (size_t i = begin; i < total; i++) {
		if (i > begin) ss << ",";
		ss << "\"" << JsonEscape(lines[i]) << "\"";
	}
	ss << "],\"count\":" << (total - begin) << ",\"total\":" << total << "}";

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleLogLevel(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	
	auto actionIt = cmd.params.find("action");
	string action = actionIt != cmd.params.end() ? actionIt->second : "get";

	if (action == "set") {
		auto levelIt = cmd.params.find("level");
		if (levelIt == cmd.params.end()) {
			resp.success = false;
			resp.error = "Missing level parameter";
			resp.errorCode = SocketErrorCode::MissingParameter;
			return resp;
		}

		string level = levelIt->second;
		std::transform(level.begin(), level.end(), level.begin(), [](unsigned char c) { return std::tolower(c); });

		auto lock = _logLevelLock.AcquireSafe();
		if (level == "debug") _logLevel = SocketLogLevel::Debug;
		else if (level == "info") _logLevel = SocketLogLevel::Info;
		else if (level == "warn") _logLevel = SocketLogLevel::Warn;
		else if (level == "error") _logLevel = SocketLogLevel::Error;
		else {
			resp.success = false;
			resp.error = "Invalid log level: " + level;
			resp.errorCode = SocketErrorCode::InvalidParameter;
			return resp;
		}
	}

	auto lock = _logLevelLock.AcquireSafe();
	string currentLevel;
	switch (_logLevel) {
		case SocketLogLevel::Debug: currentLevel = "debug"; break;
		case SocketLogLevel::Info: currentLevel = "info"; break;
		case SocketLogLevel::Warn: currentLevel = "warn"; break;
		case SocketLogLevel::Error: currentLevel = "error"; break;
	}

	stringstream ss;
	ss << "{\"level\":\"" << currentLevel << "\"}";
	
	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleSaveStateSync(Emulator* emu, const SocketCommand& cmd) {
    SocketResponse resp;
    
    auto pathIt = cmd.params.find("path");
    if (pathIt == cmd.params.end()) {
        resp.success = false;
        resp.error = "Missing path parameter";
        resp.errorCode = SocketErrorCode::MissingParameter;
        return resp;
    }
    
    string path = pathIt->second;
    uint64_t frameCount = emu->GetFrameCount();
    
    // Update the path being watched by the bridge
    YazeStateBridge::SetStatePath(path);

    // Notify YAZE via the bridge
    YazeStateBridge::NotifyStateSaved(path, frameCount);
    
    stringstream ss;
    ss << "{\"synced\":true,\"path\":\"" << JsonEscape(path) << "\",\"frame\":" << frameCount << "}";
    
    resp.success = true;
    resp.data = ss.str();
    return resp;
}

SocketResponse SocketServer::HandleSaveStateWatch(Emulator* emu, const SocketCommand& cmd) {
    SocketResponse resp;
    
    string lastState = YazeStateBridge::GetLastSyncedState();
    uint64_t lastFrame = YazeStateBridge::GetLastSyncedFrame();
    
    stringstream ss;
    ss << "{";
    ss << "\"watching\":true,";
    ss << "\"lastState\":\"" << JsonEscape(lastState) << "\",";
    ss << "\"lastFrame\":" << lastFrame;
    ss << "}";
    
    resp.success = true;
    resp.data = ss.str();
    return resp;
}

SocketResponse SocketServer::HandleStateDiff(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;

	if (!emu->IsRunning()) {
		resp.success = false;
		resp.error = "No ROM loaded";
		return resp;
	}

	// Calculate current state values
	unordered_map<string, string> currentState;
	
	currentState["frame"] = std::to_string(emu->GetFrameCount());
	currentState["paused"] = emu->IsPaused() ? "true" : "false";
	currentState["fps"] = std::to_string(emu->GetFps());
	
	// Add PC, etc if debugging
	if (emu->IsDebugging()) {
		auto debugger = emu->GetDebugger(false);
		if (debugger.GetDebugger()) {
			currentState["pc"] = FormatHex(debugger.GetDebugger()->GetProgramCounter(CpuType::Snes, true), 6);
		}
	}

	auto lock = _stateLock.AcquireSafe();
	
	stringstream ss;
	ss << "{";
	
	if (_lastState.empty()) {
		// First call, return everything
		ss << "\"firstCall\":true,";
		bool first = true;
		for (const auto& kv : currentState) {
			if (!first) ss << ",";
			ss << "\"" << kv.first << "\":\"" << JsonEscape(kv.second) << "\"";
			first = false;
		}
	} else {
		// Calculate diff
		ss << "\"firstCall\":false,";
		ss << "\"changes\":{";
		bool first = true;
		for (const auto& kv : currentState) {
			auto it = _lastState.find(kv.first);
			if (it == _lastState.end() || it->second != kv.second) {
				if (!first) ss << ",";
				ss << "\"" << kv.first << "\":\"" << JsonEscape(kv.second) << "\"";
				first = false;
			}
		}
		ss << "}";
	}
	ss << "}";

	// Update cache
	_lastState = currentState;

	resp.success = true;
	resp.data = ss.str();
	return resp;
}

SocketResponse SocketServer::HandleWatchTrigger(Emulator* emu, const SocketCommand& cmd) {
	SocketResponse resp;
	
	auto actionIt = cmd.params.find("action");
	string action = actionIt != cmd.params.end() ? actionIt->second : "list";
	
	if (action == "add") {
		// Parse addr, value, condition
		if (!cmd.HasParam("addr") || !cmd.HasParam("value") || !cmd.HasParam("condition")) {
			resp.success = false;
			resp.error = "Missing required parameters (addr, value, condition)";
			return resp;
		}
		
		uint32_t addr = 0;
		string addrStr = cmd.GetParam("addr");
		if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
			addr = std::stoul(addrStr.substr(2), nullptr, 16);
		} else {
			addr = std::stoul(addrStr, nullptr, 0);
		}

		uint16_t value = 0;
		string valStr = cmd.GetParam("value");
		if(valStr.substr(0, 2) == "0x" || valStr.substr(0, 2) == "0X") {
			value = (uint16_t)std::stoul(valStr.substr(2), nullptr, 16);
		} else {
			value = (uint16_t)std::stoul(valStr, nullptr, 0);
		}

		string condition = cmd.GetParam("condition");
		
		auto lock = _watchTriggerLock.AcquireSafe();
		WatchTrigger trigger;
		trigger.id = _nextWatchTriggerId++;
		trigger.addr = addr;
		trigger.value = value;
		trigger.condition = condition;
		trigger.enabled = true;
		trigger.triggered = false;
		
		_watchTriggers.push_back(trigger);
		
		resp.success = true;
		resp.data = "{\"id\":" + std::to_string(trigger.id) + "}";
	}
	else if (action == "remove") {
		if (!cmd.HasParam("trigger_id")) {
			resp.success = false;
			resp.error = "Missing trigger_id";
			return resp;
		}
		uint32_t id = std::stoul(cmd.GetParam("trigger_id"));
		
		auto lock = _watchTriggerLock.AcquireSafe();
		auto it = std::remove_if(_watchTriggers.begin(), _watchTriggers.end(), 
			[id](const WatchTrigger& t) { return t.id == id; });
			
		bool found = it != _watchTriggers.end();
		if (found) {
			_watchTriggers.erase(it, _watchTriggers.end());
			resp.success = true;
			resp.data = "\"OK\"";
		} else {
			resp.success = false;
			resp.error = "Trigger not found";
		}
	}
	else if (action == "list") {
		auto lock = _watchTriggerLock.AcquireSafe();
		stringstream ss;
		ss << "{\"triggers\":[";
		bool first = true;
		for (const auto& t : _watchTriggers) {
			if (!first) ss << ",";
			ss << "{\"id\":" << t.id << ",\"addr\":" << t.addr << ",\"value\":" << t.value 
			   << ",\"condition\":\"" << t.condition << "\",\"triggered\":" << (t.triggered ? "true" : "false") << "}";
			first = false;
		}
		ss << "]}";
		resp.success = true;
		resp.data = ss.str();
	}
	else if (action == "check") {
		// Check all triggers against current memory
		auto lock = _watchTriggerLock.AcquireSafe();
		stringstream ss;
		ss << "{\"triggered\":[";
		bool first = true;
		
		auto dbg = emu->GetDebugger(true);
		if (dbg.GetDebugger()) {
			auto dumper = dbg.GetDebugger()->GetMemoryDumper();
			
			for (auto& t : _watchTriggers) {
				if (!t.enabled) continue;
				
				uint8_t memVal = dumper->GetMemoryValue(MemoryType::SnesMemory, t.addr);
				
				bool match = false;
				if (t.condition == "eq") match = (memVal == t.value);
				else if (t.condition == "ne") match = (memVal != t.value);
				else if (t.condition == "gt") match = (memVal > t.value);
				else if (t.condition == "lt") match = (memVal < t.value);
				else if (t.condition == "gte") match = (memVal >= t.value);
				else if (t.condition == "lte") match = (memVal <= t.value);
				
				if (match) {
					t.triggered = true;
					if (!first) ss << ",";
					ss << "{\"id\":" << t.id << ",\"val\":" << (int)memVal << "}";
					first = false;
				}
			}
		}
		ss << "]}";
		
		resp.success = true;
		resp.data = ss.str();
	}
	else {
		resp.success = false;
		resp.error = "Unknown action";
	}
	
	return resp;
}
