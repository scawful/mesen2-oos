#pragma once
#include "pch.h"
#include "Core/Shared/Interfaces/INotificationListener.h"
#include "Core/Shared/NotificationManager.h"
#include <atomic>
#include <mutex>

typedef void(__stdcall *NotificationListenerCallback)(int, void*);

class InteropNotificationListener final : public INotificationListener
{
	inline static std::atomic<uint32_t> _callbacksInFlight = 0;

	std::mutex _callbackLock;
	NotificationListenerCallback _callback;

	static void CompleteCallback()
	{
		_callbacksInFlight.fetch_sub(1, std::memory_order_acq_rel);
	}

public:
	InteropNotificationListener(NotificationListenerCallback callback)
	{
		_callback = callback;
	}

	static bool HasCallbacksInFlight()
	{
		return _callbacksInFlight.load(std::memory_order_acquire) > 0;
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
			_callbacksInFlight.fetch_add(1, std::memory_order_acq_rel);
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
