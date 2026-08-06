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
	shared_ptr<InteropNotificationCallbackState> _callbackState = std::make_shared<InteropNotificationCallbackState>();
	bool _callbacksEnabled = true;

public:
	INotificationListener* RegisterNotificationCallback(NotificationListenerCallback callback, Emulator* emu)
	{
		auto lock = _externalNotificationListenerLock.AcquireSafe();
		if(!_callbacksEnabled || !emu) {
			return nullptr;
		}

		auto listener = shared_ptr<InteropNotificationListener>(new InteropNotificationListener(callback, _callbackState));
		_externalNotificationListeners.push_back(listener);
		emu->GetNotificationManager()->RegisterNotificationListener(listener);
		return listener.get();
	}

	void UnregisterNotificationCallback(INotificationListener *listener)
	{
		auto lock = _externalNotificationListenerLock.AcquireSafe();
		auto match = std::find_if(
			_externalNotificationListeners.begin(),
			_externalNotificationListeners.end(),
			[=](const shared_ptr<InteropNotificationListener>& ptr) { return ptr.get() == listener; }
		);
		if(match != _externalNotificationListeners.end()) {
			// Close this listener's callback admission before making it
			// unreachable to a concurrent registry shutdown.
			(*match)->Disable();
			_externalNotificationListeners.erase(match);
		}
	}

	void DisableCallbacks()
	{
		auto lock = _externalNotificationListenerLock.AcquireSafe();
		_callbacksEnabled = false;
		for(shared_ptr<InteropNotificationListener>& listener : _externalNotificationListeners) {
			listener->Disable();
		}
		_externalNotificationListeners.clear();
	}

	void DisableCallbacksAndWait()
	{
		{
			auto lock = _externalNotificationListenerLock.AcquireSafe();
			_callbacksEnabled = false;
			for(shared_ptr<InteropNotificationListener>& listener : _externalNotificationListeners) {
				listener->Disable();
			}
			_externalNotificationListeners.clear();
		}
		// The owner state also counts callbacks from listeners unregistered just
		// before shutdown. NotificationManager can still be executing one after
		// it has been removed from this registry's active vector.
		_callbackState->WaitForCallbacks();
	}

	void EnableCallbacks()
	{
		auto lock = _externalNotificationListenerLock.AcquireSafe();
		_callbacksEnabled = true;
	}
};
