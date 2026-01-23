#pragma once
#include "pch.h"
#include "Core/Shared/Interfaces/INotificationListener.h"
#include "Core/Shared/NotificationManager.h"

typedef void(__stdcall *NotificationListenerCallback)(int, void*);

class InteropNotificationListener : public INotificationListener
{
	NotificationListenerCallback _callback;

public:
	InteropNotificationListener(NotificationListenerCallback callback)
	{
		_callback = callback;
	}

	virtual ~InteropNotificationListener()
	{
	}

	void ProcessNotification(ConsoleNotificationType type, void* parameter)
	{
		if(_callback) {
			_callback((int)type, parameter);
		}
	}
};
