#pragma once
#include "pch.h"
#include "Core/Shared/Interfaces/INotificationListener.h"
#include "Core/Shared/NotificationManager.h"
#include "Core/Shared/Emulator.h"
#include "Utilities/SimpleLock.h"
#include "InteropNotificationListener.h"

typedef void(__stdcall *NotificationListenerCallback)(int, void*);

class InteropNotificationListeners
{
	SimpleLock _externalNotificationListenerLock;
	vector<shared_ptr<InteropNotificationListener>> _externalNotificationListeners;
	bool _callbacksEnabled = true;

public:
	INotificationListener* RegisterNotificationCallback(NotificationListenerCallback callback, Emulator* emu)
	{
		auto lock = _externalNotificationListenerLock.AcquireSafe();
		if(!_callbacksEnabled || !emu) {
			return nullptr;
		}

		auto listener = shared_ptr<InteropNotificationListener>(new InteropNotificationListener(callback));
		_externalNotificationListeners.push_back(listener);
		emu->GetNotificationManager()->RegisterNotificationListener(listener);
		return listener.get();
	}

	void UnregisterNotificationCallback(INotificationListener *listener)
	{
		shared_ptr<InteropNotificationListener> removedListener;
		{
			auto lock = _externalNotificationListenerLock.AcquireSafe();
			auto match = std::find_if(
				_externalNotificationListeners.begin(),
				_externalNotificationListeners.end(),
				[=](const shared_ptr<InteropNotificationListener>& ptr) { return ptr.get() == listener; }
			);
			if(match != _externalNotificationListeners.end()) {
				removedListener = *match;
				_externalNotificationListeners.erase(match);
			}
		}

		if(removedListener) {
			removedListener->Disable();
		}
	}

	void DisableCallbacks()
	{
		vector<shared_ptr<InteropNotificationListener>> listeners;
		{
			auto lock = _externalNotificationListenerLock.AcquireSafe();
			_callbacksEnabled = false;
			listeners.swap(_externalNotificationListeners);
		}

		// Disable outside the owner lock. A callback can unregister itself, and
		// Disable() must be able to wait for any callback already in progress.
		for(shared_ptr<InteropNotificationListener>& listener : listeners) {
			listener->Disable();
		}
	}
};
