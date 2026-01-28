#pragma once
#include "pch.h"
#include "Utilities/SimpleLock.h"
#include <thread>
#include <atomic>
#include <string>
#include <functional>

class Emulator;

// YAZE state bridge for bidirectional save state synchronization
class YazeStateBridge {
public:
	YazeStateBridge(Emulator* emu);
	~YazeStateBridge();

	// Start/stop the bridge
	void Start();
	void Stop();
	bool IsRunning() const { return _running; }

	// Set the path to watch for state updates
	static void SetStatePath(const std::string& path);
	static std::string GetStatePath();
	static void NotifyStateSaved(const std::string& statePath, uint64_t frameCount);
	static std::string GetLastSyncedState();
	static uint64_t GetLastSyncedFrame();
	static std::string GetLastError();
	static uint64_t GetLastErrorTimeMs();

private:
	Emulator* _emu;
	std::atomic<bool> _running;
	std::unique_ptr<std::thread> _watchThread;
	
	// YAZE state file path
	static std::string _statePath;
	static constexpr const char* kYazeStateNotifyPath = "/tmp/oos_yaze_state_notify";
	
	// Last synced state info
	static std::string _lastSyncedState;
	static uint64_t _lastSyncedFrame;
	static std::string _lastError;
	static uint64_t _lastErrorTimeMs;
	static SimpleLock _syncLock;

	void WatchLoop();
	bool LoadYazeState(const std::string& path);
	void NotifyYaze(const std::string& statePath, uint64_t frameCount);
};
