#include "pch.h"
#include "Shared/YazeProject.h"
#include "Utilities/FolderUtilities.h"

static string TrimValue(const string& value)
{
	size_t start = value.find_first_not_of(" \t\r\n");
	if(start == string::npos) {
		return "";
	}
	size_t end = value.find_last_not_of(" \t\r\n");
	return value.substr(start, end - start + 1);
}

static bool IsAbsolutePath(const string& path)
{
	return (!path.empty() && (path[0] == '/' || path[0] == '\\')) || (path.size() > 1 && path[1] == ':');
}

static bool FileExists(const string& path)
{
	ifstream file(path, std::ios::in | std::ios::binary);
	return file.good();
}

bool YazeProject::IsProjectFile(const string& path)
{
	string ext = FolderUtilities::GetExtension(path);
	return ext == ".yaze";
}

bool YazeProject::Resolve(const string& projectPath, YazeProjectInfo& info, string& error)
{
	ifstream file(projectPath, std::ios::in | std::ios::binary);
	if(!file) {
		error = "Could not open project file: " + projectPath;
		return false;
	}

	unordered_map<string, string> values;
	string section;
	string line;
	while(std::getline(file, line)) {
		line = TrimValue(line);
		if(line.empty() || line[0] == '#' || line[0] == ';') {
			continue;
		}
		if(line.front() == '[' && line.back() == ']') {
			section = TrimValue(line.substr(1, line.size() - 2));
			continue;
		}
		size_t separator = line.find('=');
		if(separator == string::npos) {
			continue;
		}
		values[section + "." + TrimValue(line.substr(0, separator))] = TrimValue(line.substr(separator + 1));
	}

	string folder = FolderUtilities::GetFolderName(projectPath);
	auto resolve = [&](const string& key) -> string {
		auto it = values.find(key);
		if(it == values.end() || it->second.empty()) {
			return "";
		}
		return IsAbsolutePath(it->second) ? it->second : FolderUtilities::CombinePath(folder, it->second);
	};

	info = {};
	info.ProjectPath = projectPath;
	info.Name = values["project.name"];

	//build_target is the assembled ROM that should run; rom_filename is the editable base ROM.
	//A declared build_target that is missing means the hack is unbuilt; running the base ROM instead would look like the hack.
	string buildTarget = resolve("build.build_target");
	string baseRom = resolve("files.rom_filename");
	if(!buildTarget.empty()) {
		if(!FileExists(buildTarget)) {
			error = "Project build target not found: " + buildTarget + " (build the project first)";
			return false;
		}
		info.RomPath = buildTarget;
	} else if(!baseRom.empty() && FileExists(baseRom)) {
		info.RomPath = baseRom;
	} else {
		error = "Project ROM not found (rom_filename='" + baseRom + "')";
		return false;
	}

	string symbols = resolve("files.symbols_filename");
	if(!symbols.empty() && FileExists(symbols)) {
		info.SymbolsPath = symbols;
	}

	string profile = FolderUtilities::CombinePath(folder, FolderUtilities::GetFilename(projectPath, false) + ".mesen.json");
	if(FileExists(profile)) {
		info.ProfilePath = profile;
	}
	return true;
}
