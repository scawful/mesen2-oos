#pragma once
#include "pch.h"
#include "Core/Shared/Interfaces/INotificationListener.h"
#include "Core/Shared/NotificationManager.h"
#include <atomic>
#include <condition_variable>
#include <mutex>

typedef void(__stdcall *NotificationListenerCallback)(int, void*);

class InteropNotificationCallbackState
{
	std::atomic<uint32_t> _callbacksInFlight = 0;
	std::mutex _callbackWaitLock;
	std::condition_variable _callbackFinished;

public:
	void BeginCallback()
	{
		_callbacksInFlight.fetch_add(1, std::memory_order_acq_rel);
	}

	void CompleteCallback()
	{
		if(_callbacksInFlight.fetch_sub(1, std::memory_order_acq_rel) == 1) {
			std::unique_lock<std::mutex> lock(_callbackWaitLock);
			lock.unlock();
			_callbackFinished.notify_all();
		}
	}

	void WaitForCallbacks()
	{
		std::unique_lock<std::mutex> lock(_callbackWaitLock);
		_callbackFinished.wait(lock, [this]() {
			return _callbacksInFlight.load(std::memory_order_acquire) == 0;
		});
	}
};

class InteropNotificationListener final : public INotificationListener
{
	inline static std::atomic<uint32_t> _callbacksInFlight = 0;
	inline static thread_local uint32_t _currentThreadCallbackDepth = 0;
	inline static std::mutex _callbackWaitLock;
	inline static std::condition_variable _callbackFinished;

	shared_ptr<InteropNotificationCallbackState> _ownerCallbackState;
	std::mutex _callbackLock;
	NotificationListenerCallback _callback;

	void CompleteCallback()
	{
		_currentThreadCallbackDepth--;
		_ownerCallbackState->CompleteCallback();
		if(_callbacksInFlight.fetch_sub(1, std::memory_order_acq_rel) == 1) {
			std::unique_lock<std::mutex> lock(_callbackWaitLock);
			lock.unlock();
			_callbackFinished.notify_all();
		}
	}

public:
	InteropNotificationListener(NotificationListenerCallback callback, shared_ptr<InteropNotificationCallbackState> ownerCallbackState)
	{
		_callback = callback;
		_ownerCallbackState = ownerCallbackState;
	}

	static bool HasCallbacksInFlight()
	{
		return _callbacksInFlight.load(std::memory_order_acquire) > 0;
	}

	static bool IsCallbackOnCurrentThread()
	{
		return _currentThreadCallbackDepth > 0;
	}

	static void WaitForCallbacks()
	{
		std::unique_lock<std::mutex> lock(_callbackWaitLock);
		_callbackFinished.wait(lock, []() {
			return _callbacksInFlight.load(std::memory_order_acquire) == 0;
		});
	}

	void Disable()
	{
		std::unique_lock<std::mutex> lock(_callbackLock);
		_callback = nullptr;
	}

	void ProcessNotification(ConsoleNotificationType type, void* parameter)
	{
		NotificationListenerCallback callback;
		{
			std::unique_lock<std::mutex> lock(_callbackLock);
			callback = _callback;
			if(!callback) {
				return;
			}
			_ownerCallbackState->BeginCallback();
			_callbacksInFlight.fetch_add(1, std::memory_order_acq_rel);
			_currentThreadCallbackDepth++;
		}

		try {
			callback((int)type, parameter);
		} catch(...) {
			CompleteCallback();
			throw;
		}
		CompleteCallback();
	}
};
