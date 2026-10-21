#pragma once
#include "pch.h"

// Minimal reader for yaze project files (.yaze, INI format). Mesen only needs the
// ROM to run, the symbol file and an optional sidecar capture/tool profile
// (<project stem>.mesen.json next to the .yaze file).
struct YazeProjectInfo
{
	string ProjectPath;
	string Name;
	string RomPath;
	string SymbolsPath;
	string ProfilePath;
};

class YazeProject
{
public:
	static bool IsProjectFile(const string& path);
	static bool Resolve(const string& projectPath, YazeProjectInfo& info, string& error);
};
