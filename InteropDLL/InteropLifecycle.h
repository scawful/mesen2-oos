#pragma once

#include "pch.h"

class Emulator;

// Pins the process-wide emulator and its interop-owned devices for the full
// duration of an exported native call.  Release() first closes admission, then
// waits for every admitted lease to drain before moving or destroying owners.
class InteropEmulatorLease final
{
private:
	Emulator* _emulator = nullptr;
	bool _active = false;

	explicit InteropEmulatorLease(Emulator* emulator);
	friend InteropEmulatorLease AcquireInteropEmulatorLease();

public:
	InteropEmulatorLease() = default;
	InteropEmulatorLease(const InteropEmulatorLease&) = delete;
	InteropEmulatorLease& operator=(const InteropEmulatorLease&) = delete;
	InteropEmulatorLease(InteropEmulatorLease&&) = delete;
	InteropEmulatorLease& operator=(InteropEmulatorLease&&) = delete;
	~InteropEmulatorLease();

	explicit operator bool() const { return _emulator != nullptr; }
	Emulator* Get() const { return _emulator; }
	Emulator* operator->() const { return _emulator; }
};

InteropEmulatorLease AcquireInteropEmulatorLease();
bool InteropEmulatorLeaseHeldByCurrentThread();

#define INTEROP_EMU_LEASE_OR_RETURN() \
	auto interopEmuLease = AcquireInteropEmulatorLease(); \
	if(!interopEmuLease) { return; }

#define INTEROP_EMU_LEASE_OR_RETURN_VALUE(value) \
	auto interopEmuLease = AcquireInteropEmulatorLease(); \
	if(!interopEmuLease) { return value; }
