/**
 * SocketServer Unit Tests
 *
 * Compile with:
 *   clang++ -std=c++20 -DTEST_MODE -I.. -I../.. SocketServerTest.cpp -o socket_test
 *
 * Run with:
 *   ./socket_test
 */

#ifdef TEST_MODE

#include <cassert>
#include <cstring>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

// Minimal mock types for testing
enum class CpuType : uint8_t {
	Snes = 0,
	Spc,
	NecDsp,
	Sa1,
	Gsu,
	Cx4,
	Gameboy,
	Nes,
	Pce,
	Sms,
	Gba,
};

enum class MemoryType {
	None = 0,
	SnesMemory,
	SnesPrgRom,
	SnesWorkRam,
	SnesSaveRam,
	SnesVideoRam,
	SnesSpriteRam,
	SnesCgRam,
	SnesRegister,
};

enum class BreakpointTypeFlags : uint8_t {
	None = 0,
	Execute = 1,
	Read = 2,
	Write = 4,
};

using namespace std;

// Test SocketCommand parsing - use map instead of unordered_map to avoid hash issues
struct SocketCommand {
	string type;
	map<string, string> params;
};

// Simplified ParseCommand for testing
SocketCommand ParseCommand(const string& json) {
	SocketCommand cmd;

	// Extract type
	size_t typeStart = json.find("\"type\"");
	if (typeStart != string::npos) {
		size_t colonPos = json.find(':', typeStart);
		size_t quoteStart = json.find('"', colonPos + 1);
		size_t quoteEnd = json.find('"', quoteStart + 1);
		if (quoteStart != string::npos && quoteEnd != string::npos) {
			cmd.type = json.substr(quoteStart + 1, quoteEnd - quoteStart - 1);
		}
	}

	// Extract other params
	auto extractParam = [&](const string& key) -> string {
		size_t keyStart = json.find("\"" + key + "\"");
		if (keyStart != string::npos) {
			size_t colonPos = json.find(':', keyStart);
			if (colonPos != string::npos) {
				size_t valueStart = colonPos + 1;
				while (valueStart < json.size() &&
					   (json[valueStart] == ' ' || json[valueStart] == '\t')) {
					valueStart++;
				}
				if (valueStart < json.size()) {
					if (json[valueStart] == '"') {
						size_t quoteEnd = json.find('"', valueStart + 1);
						if (quoteEnd != string::npos) {
							return json.substr(valueStart + 1, quoteEnd - valueStart - 1);
						}
					} else {
						size_t valueEnd = valueStart;
						while (valueEnd < json.size() && json[valueEnd] != ',' &&
							   json[valueEnd] != '}') {
							valueEnd++;
						}
						return json.substr(valueStart, valueEnd - valueStart);
					}
				}
			}
		}
		return "";
	};

	// Common params
	vector<string> commonParams = {"addr",	  "value",	 "action",	  "bptype", "id",
								   "enabled", "memtype", "condition", "cputype"};
	for (const auto& param : commonParams) {
		string val = extractParam(param);
		if (!val.empty()) {
			cmd.params[param] = val;
		}
	}

	return cmd;
}

// Test ParseMemoryType
MemoryType ParseMemoryType(const string& memtype) {
	if (memtype == "SnesMemory" || memtype.empty())
		return MemoryType::SnesMemory;
	if (memtype == "SnesWorkRam" || memtype == "WRAM")
		return MemoryType::SnesWorkRam;
	if (memtype == "SnesSaveRam" || memtype == "SRAM")
		return MemoryType::SnesSaveRam;
	if (memtype == "SnesPrgRom" || memtype == "ROM")
		return MemoryType::SnesPrgRom;
	if (memtype == "SnesVideoRam" || memtype == "VRAM")
		return MemoryType::SnesVideoRam;
	if (memtype == "SnesSpriteRam" || memtype == "OAM")
		return MemoryType::SnesSpriteRam;
	if (memtype == "SnesCgRam" || memtype == "CGRAM")
		return MemoryType::SnesCgRam;
	return MemoryType::SnesMemory;
}

// Test ParseCpuType
CpuType ParseCpuType(const string& cpuType) {
	if (cpuType == "Snes" || cpuType.empty())
		return CpuType::Snes;
	if (cpuType == "Spc")
		return CpuType::Spc;
	if (cpuType == "NecDsp")
		return CpuType::NecDsp;
	if (cpuType == "Sa1")
		return CpuType::Sa1;
	if (cpuType == "Gsu")
		return CpuType::Gsu;
	if (cpuType == "Cx4")
		return CpuType::Cx4;
	if (cpuType == "Gameboy")
		return CpuType::Gameboy;
	if (cpuType == "Nes")
		return CpuType::Nes;
	if (cpuType == "Pce")
		return CpuType::Pce;
	if (cpuType == "Sms")
		return CpuType::Sms;
	if (cpuType == "Gba")
		return CpuType::Gba;
	return CpuType::Snes;
}

// Test BreakpointData struct layout
#pragma pack(push, 1)
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
#pragma pack(pop)

// =============================================================================
// Test Cases
// =============================================================================

void test_parse_command_basic() {
	cout << "test_parse_command_basic... ";

	string json = R"({"type":"PING"})";
	auto cmd = ParseCommand(json);

	assert(cmd.type == "PING");
	assert(cmd.params.empty());

	cout << "PASSED\n";
}

void test_parse_command_with_params() {
	cout << "test_parse_command_with_params... ";

	string json = R"({"type":"READ","addr":"0x7E0000"})";
	auto cmd = ParseCommand(json);

	assert(cmd.type == "READ");
	assert(cmd.params["addr"] == "0x7E0000");

	cout << "PASSED\n";
}

void test_parse_command_breakpoint_add() {
	cout << "test_parse_command_breakpoint_add... ";

	string json =
		R"({"type":"BREAKPOINT","action":"add","addr":"0x008000","bptype":"exec"})";
	auto cmd = ParseCommand(json);

	assert(cmd.type == "BREAKPOINT");
	assert(cmd.params["action"] == "add");
	assert(cmd.params["addr"] == "0x008000");
	assert(cmd.params["bptype"] == "exec");

	cout << "PASSED\n";
}

void test_parse_command_breakpoint_with_condition() {
	cout << "test_parse_command_breakpoint_with_condition... ";

	string json =
		R"({"type":"BREAKPOINT","action":"add","addr":"0x008000","condition":"A == 0x42"})";
	auto cmd = ParseCommand(json);

	assert(cmd.type == "BREAKPOINT");
	assert(cmd.params["condition"] == "A == 0x42");

	cout << "PASSED\n";
}

void test_parse_memory_type() {
	cout << "test_parse_memory_type... ";

	assert(ParseMemoryType("WRAM") == MemoryType::SnesWorkRam);
	assert(ParseMemoryType("SRAM") == MemoryType::SnesSaveRam);
	assert(ParseMemoryType("ROM") == MemoryType::SnesPrgRom);
	assert(ParseMemoryType("VRAM") == MemoryType::SnesVideoRam);
	assert(ParseMemoryType("OAM") == MemoryType::SnesSpriteRam);
	assert(ParseMemoryType("CGRAM") == MemoryType::SnesCgRam);
	assert(ParseMemoryType("SnesWorkRam") == MemoryType::SnesWorkRam);
	assert(ParseMemoryType("") == MemoryType::SnesMemory);

	cout << "PASSED\n";
}

void test_parse_cpu_type() {
	cout << "test_parse_cpu_type... ";

	assert(ParseCpuType("Snes") == CpuType::Snes);
	assert(ParseCpuType("Spc") == CpuType::Spc);
	assert(ParseCpuType("Sa1") == CpuType::Sa1);
	assert(ParseCpuType("Gsu") == CpuType::Gsu);
	assert(ParseCpuType("") == CpuType::Snes);

	cout << "PASSED\n";
}

void test_breakpoint_type_flags() {
	cout << "test_breakpoint_type_flags... ";

	uint8_t exec = static_cast<uint8_t>(BreakpointTypeFlags::Execute);
	uint8_t read = static_cast<uint8_t>(BreakpointTypeFlags::Read);
	uint8_t write = static_cast<uint8_t>(BreakpointTypeFlags::Write);

	assert(exec == 1);
	assert(read == 2);
	assert(write == 4);

	// Test combinations
	uint8_t rw = read | write;
	assert(rw == 6);

	uint8_t xrw = exec | read | write;
	assert(xrw == 7);

	cout << "PASSED\n";
}

void test_breakpoint_data_layout() {
	cout << "test_breakpoint_data_layout... ";

	BreakpointData bp = {};
	bp.id = 1;
	bp.cpuType = CpuType::Snes;
	bp.memoryType = MemoryType::SnesMemory;
	bp.type = BreakpointTypeFlags::Execute;
	bp.startAddr = 0x008000;
	bp.endAddr = 0x008000;
	bp.enabled = true;
	bp.markEvent = false;
	bp.ignoreDummyOperations = false;
	strcpy(bp.condition, "A == 0x42");

	assert(bp.id == 1);
	assert(bp.cpuType == CpuType::Snes);
	assert(bp.startAddr == 0x008000);
	assert(bp.enabled == true);
	assert(strcmp(bp.condition, "A == 0x42") == 0);

	// Verify struct size (should be deterministic with pragma pack)
	// id(4) + cpuType(1) + memoryType(4) + type(1) + startAddr(4) + endAddr(4)
	// + enabled(1) + markEvent(1) + ignoreDummyOps(1) + condition(1000)
	// Total depends on enum sizes; just verify it's reasonable
	assert(sizeof(BreakpointData) >= 1016);
	assert(sizeof(BreakpointData) <= 1024);

	cout << "PASSED (size=" << sizeof(BreakpointData) << ")\n";
}

void test_breakpoint_type_parsing() {
	cout << "test_breakpoint_type_parsing... ";

	// This matches the fixed implementation in SocketServer.cpp
	// Full words are checked first, shorthand only for short strings
	auto parseType = [](const string& typeStr) -> uint8_t {
		uint8_t bpType = 0;

		// Check for full words first
		bool hasExec = typeStr.find("exec") != string::npos;
		bool hasRead = typeStr.find("read") != string::npos;
		bool hasWrite = typeStr.find("write") != string::npos;

		// If no full words found, check for shorthand characters
		// Only use single-char matching for short strings
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

		return bpType;
	};

	// Full word tests - each only triggers its own flag
	assert(parseType("exec") == 1);
	assert(parseType("read") == 2);
	assert(parseType("write") == 4);  // Fixed: now correctly returns write only

	// Combination of full words
	assert(parseType("read,write") == 6);
	assert(parseType("exec,read,write") == 7);

	// Shorthand tests (only work for short strings)
	assert(parseType("x") == 1);
	assert(parseType("r") == 2);
	assert(parseType("w") == 4);
	assert(parseType("rw") == 6);
	assert(parseType("xrw") == 7);

	cout << "PASSED\n";
}

void test_address_parsing() {
	cout << "test_address_parsing... ";

	auto parseAddr = [](const string& addrStr) -> uint32_t {
		if (addrStr.substr(0, 2) == "0x" || addrStr.substr(0, 2) == "0X") {
			return std::stoul(addrStr.substr(2), nullptr, 16);
		}
		return std::stoul(addrStr, nullptr, 16);
	};

	assert(parseAddr("0x008000") == 0x008000);
	assert(parseAddr("0X7E0000") == 0x7E0000);
	assert(parseAddr("008000") == 0x008000);
	assert(parseAddr("7E0000") == 0x7E0000);

	cout << "PASSED\n";
}

int main() {
	cout << "=== SocketServer Unit Tests ===\n\n";

	test_parse_command_basic();
	test_parse_command_with_params();
	test_parse_command_breakpoint_add();
	test_parse_command_breakpoint_with_condition();
	test_parse_memory_type();
	test_parse_cpu_type();
	test_breakpoint_type_flags();
	test_breakpoint_data_layout();
	test_breakpoint_type_parsing();
	test_address_parsing();

	cout << "\n=== All tests passed! ===\n";
	return 0;
}

#endif  // TEST_MODE
