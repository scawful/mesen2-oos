#include "pch.h"
#include "YazeStateBridge.h"
#include "Emulator.h"
#include "SaveStateManager.h"
#include "MessageManager.h"
#include <sys/stat.h>
#include <unistd.h>
#include <fstream>
#include <chrono>

// Static member definitions
std::string YazeStateBridge::_lastSyncedState;
uint64_t YazeStateBridge::_lastSyncedFrame = 0;
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
		return false;
	}
	
	try {
		bool success = _emu->GetSaveStateManager()->LoadState(path);
		if (success) {
			auto lock = _syncLock.AcquireSafe();
			_lastSyncedState = path;
			_lastSyncedFrame = _emu->GetFrameCount();
			MessageManager::Log("[YazeStateBridge] Loaded state from YAZE: " + path);
		}
		return success;
	} catch (...) {
		return false;
	}
}

void YazeStateBridge::NotifyYaze(const std::string& statePath, uint64_t frameCount) {
	// Write notification file for YAZE to pick up
	try {
		std::ofstream notifyFile(kYazeStateNotifyPath);
		if (notifyFile.is_open()) {
			notifyFile << statePath << "\n";
			notifyFile << frameCount << "\n";
			notifyFile.close();
		}
	} catch (...) {
		// Ignore notification errors
	}
}

void YazeStateBridge::NotifyStateSaved(const std::string& statePath, uint64_t frameCount) {
	auto lock = _syncLock.AcquireSafe();
	_lastSyncedState = statePath;
	_lastSyncedFrame = frameCount;
	
	// Write notification file for YAZE to pick up
	try {
		std::ofstream notifyFile(kYazeStateNotifyPath);
		if (notifyFile.is_open()) {
			notifyFile << statePath << "\n";
			notifyFile << frameCount << "\n";
			notifyFile.close();
		}
	} catch (...) {
		// Ignore notification errors
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
