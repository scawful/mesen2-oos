#include "pch.h"
#include "YazeStateBridge.h"
#include "Emulator.h"
#include "SaveStateManager.h"
#include "MessageManager.h"
#include <sys/stat.h>
#include <unistd.h>
#include <fstream>
#include <chrono>
#include <filesystem>

namespace fs = std::filesystem;

static uint64_t NowMs()
{
	return std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::system_clock::now().time_since_epoch()
	).count();
}

static bool WriteNotifyFileAtomic(const std::string& notifyPath, const std::string& statePath, uint64_t frameCount, std::string& error)
{
	fs::path target = fs::u8path(notifyPath);
	fs::path temp = target;
	temp += ".tmp";

	std::ofstream notifyFile(temp, std::ios::out | std::ios::trunc);
	if (!notifyFile.is_open()) {
		error = "Failed to open notify file";
		return false;
	}

	notifyFile << statePath << "\n";
	notifyFile << frameCount << "\n";
	notifyFile.flush();
	if (!notifyFile.good()) {
		error = "Failed to write notify file";
		notifyFile.close();
		std::error_code cleanupError;
		fs::remove(temp, cleanupError);
		return false;
	}
	notifyFile.close();

	std::error_code renameError;
	fs::rename(temp, target, renameError);
	if (renameError) {
		std::error_code removeError;
		fs::remove(target, removeError);
		fs::rename(temp, target, renameError);
	}
	if (renameError) {
		error = "Failed to update notify file: " + renameError.message();
		std::error_code cleanupError;
		fs::remove(temp, cleanupError);
		return false;
	}
	return true;
}

// Static member definitions
std::string YazeStateBridge::_lastSyncedState;
uint64_t YazeStateBridge::_lastSyncedFrame = 0;
std::string YazeStateBridge::_lastError;
uint64_t YazeStateBridge::_lastErrorTimeMs = 0;
// Default path
std::string YazeStateBridge::_statePath = "/tmp/oos_yaze_state.mss";
SimpleLock YazeStateBridge::_syncLock;

YazeStateBridge::YazeStateBridge(Emulator* emu) : _emu(emu), _running(false) {
}

YazeStateBridge::~YazeStateBridge() {
	Stop();
}

void YazeStateBridge::Start() {
	if (_running) return;
	
	_running = true;
	_watchThread = std::make_unique<std::thread>(&YazeStateBridge::WatchLoop, this);
	MessageManager::Log("[YazeStateBridge] Started watching " + _statePath);
}

void YazeStateBridge::Stop() {
	if (!_running) return;
	
	_running = false;
	if (_watchThread && _watchThread->joinable()) {
		_watchThread->join();
	}
	_watchThread.reset();
	MessageManager::Log("[YazeStateBridge] Stopped");
}

void YazeStateBridge::SetStatePath(const std::string& path) {
	auto lock = _syncLock.AcquireSafe();
	_statePath = path;
	MessageManager::Log("[YazeStateBridge] Watching new path: " + path);
}

std::string YazeStateBridge::GetStatePath() {
	auto lock = _syncLock.AcquireSafe();
	return _statePath;
}

void YazeStateBridge::WatchLoop() {
	std::string currentPath;
	{
		auto lock = _syncLock.AcquireSafe();
		currentPath = _statePath;
	}

	struct stat lastStat = {};
	bool fileExists = (stat(currentPath.c_str(), &lastStat) == 0);
	
	while (_running) {
		// Update path if changed
		std::string newPath;
		{
			auto lock = _syncLock.AcquireSafe();
			newPath = _statePath;
		}
		
		if (newPath != currentPath) {
			currentPath = newPath;
			fileExists = (stat(currentPath.c_str(), &lastStat) == 0);
		}

		struct stat currentStat;
		if (stat(currentPath.c_str(), &currentStat) == 0) {
			// File exists - check if it was modified
			if (!fileExists || 
			    currentStat.st_mtime != lastStat.st_mtime ||
			    currentStat.st_size != lastStat.st_size) {
				// File was created or modified
				if (LoadYazeState(currentPath)) {
					lastStat = currentStat;
					fileExists = true;
				}
			}
		} else {
			// File doesn't exist
			fileExists = false;
		}
		
		// Check every 100ms
		std::this_thread::sleep_for(std::chrono::milliseconds(100));
	}
}

bool YazeStateBridge::LoadYazeState(const std::string& path) {
	if (!_emu || !_emu->IsRunning()) {
		auto lock = _syncLock.AcquireSafe();
		_lastError = "Emulator not running";
		_lastErrorTimeMs = NowMs();
		return false;
	}
	
	try {
		bool wasPaused = _emu->IsPaused();
		if(!wasPaused) {
			_emu->Pause();
		}

		bool success = _emu->GetSaveStateManager()->LoadState(path);
		if(!wasPaused) {
			_emu->Resume();
		}

		if (success) {
			auto lock = _syncLock.AcquireSafe();
			_lastSyncedState = path;
			_lastSyncedFrame = _emu->GetFrameCount();
			_lastError.clear();
			_lastErrorTimeMs = 0;
			MessageManager::Log("[YazeStateBridge] Loaded state from YAZE: " + path);
		} else {
			auto lock = _syncLock.AcquireSafe();
			_lastError = "Failed to load YAZE state";
			_lastErrorTimeMs = NowMs();
		}
		return success;
	} catch (const std::exception& e) {
		auto lock = _syncLock.AcquireSafe();
		_lastError = string("Exception while loading YAZE state: ") + e.what();
		_lastErrorTimeMs = NowMs();
		return false;
	} catch (...) {
		auto lock = _syncLock.AcquireSafe();
		_lastError = "Unknown error while loading YAZE state";
		_lastErrorTimeMs = NowMs();
		return false;
	}
}

void YazeStateBridge::NotifyYaze(const std::string& statePath, uint64_t frameCount) {
	// Write notification file for YAZE to pick up
	std::string error;
	if(!WriteNotifyFileAtomic(kYazeStateNotifyPath, statePath, frameCount, error)) {
		auto lock = _syncLock.AcquireSafe();
		_lastError = error;
		_lastErrorTimeMs = NowMs();
	}
}

void YazeStateBridge::NotifyStateSaved(const std::string& statePath, uint64_t frameCount) {
	auto lock = _syncLock.AcquireSafe();
	_lastSyncedState = statePath;
	_lastSyncedFrame = frameCount;
	
	// Write notification file for YAZE to pick up
	lock.Release();
	std::string error;
	if(!WriteNotifyFileAtomic(kYazeStateNotifyPath, statePath, frameCount, error)) {
		auto errLock = _syncLock.AcquireSafe();
		_lastError = error;
		_lastErrorTimeMs = NowMs();
	} else {
		auto errLock = _syncLock.AcquireSafe();
		_lastError.clear();
		_lastErrorTimeMs = 0;
	}
}

std::string YazeStateBridge::GetLastSyncedState() {
	auto lock = _syncLock.AcquireSafe();
	return _lastSyncedState;
}

uint64_t YazeStateBridge::GetLastSyncedFrame() {
	auto lock = _syncLock.AcquireSafe();
	return _lastSyncedFrame;
}

std::string YazeStateBridge::GetLastError() {
	auto lock = _syncLock.AcquireSafe();
	return _lastError;
}

uint64_t YazeStateBridge::GetLastErrorTimeMs() {
	auto lock = _syncLock.AcquireSafe();
	return _lastErrorTimeMs;
}
