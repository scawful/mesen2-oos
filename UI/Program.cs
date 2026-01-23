using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.ReactiveUI;
using Avalonia.Media;
using Mesen.Config;
using Mesen.Utilities;
using System;
using System.Linq;
using System.IO;
using System.IO.Compression;
using System.Collections.Generic;
using System.Reflection;
using System.Threading.Tasks;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Mesen.Interop;

namespace Mesen
{
	class Program
	{
		// Initialization code. Don't use any Avalonia, third-party APIs or any
		// SynchronizationContext-reliant code before AppMain is called: things aren't initialized
		// yet and stuff might break.

		public static string OriginalFolder { get; private set; }
		public static string[] CommandLineArgs { get; private set; } = Array.Empty<string>();

		public static string ExePath => Process.GetCurrentProcess().MainModule?.FileName ?? Path.Join(Path.GetDirectoryName(AppContext.BaseDirectory), "Mesen.exe");

		static Program()
		{
			try {
				Program.OriginalFolder = Environment.CurrentDirectory;
			} catch {
				Program.OriginalFolder = Path.GetDirectoryName(ExePath) ?? "";
			}
		}

		[STAThread]
		public static int Main(string[] args)
		{
			if(!System.Diagnostics.Debugger.IsAttached) {
				NativeLibrary.SetDllImportResolver(Assembly.GetExecutingAssembly(), DllImportResolver);
				NativeLibrary.SetDllImportResolver(typeof(SkiaSharp.SKGraphics).Assembly, DllImportResolver);
				NativeLibrary.SetDllImportResolver(typeof(HarfBuzzSharp.Blob).Assembly, DllImportResolver);
			}

			if(args.Length >= 4 && args[0] == "--update") {
				UpdateHelper.AttemptUpdate(args[1], args[2], args[3], args.Contains("admin"));
				return 0;
			}

			Environment.CurrentDirectory = ConfigManager.HomeFolder;

			if(!File.Exists(ConfigManager.GetConfigFile())) {
				//Could not find configuration file, show wizard
				ExtractNativeDependencies(ConfigManager.HomeFolder);
				App.ShowConfigWindow = true;
				BuildAvaloniaApp().StartWithClassicDesktopLifetime(args, ShutdownMode.OnMainWindowClose);
				if(File.Exists(ConfigManager.GetConfigFile())) {
					//Configuration done, restart process
					Process.Start(Program.ExePath);
				}
				return 0;
			}

			//Start loading config file in a separate thread
			Task.Run(() => ConfigManager.LoadConfig());

			//Extract core dll & other native dependencies
			ExtractNativeDependencies(ConfigManager.HomeFolder);

			if(CommandLineHelper.IsTestRunner(args)) {
				return TestRunner.Run(args);
			}

			using SingleInstance instance = SingleInstance.Instance;
			instance.Init(args);
			if(instance.FirstInstance) {
				Program.CommandLineArgs = (string[])args.Clone();
				BuildAvaloniaApp().StartWithClassicDesktopLifetime(args, ShutdownMode.OnMainWindowClose);
				EmuApi.Release();
			}

			return 0;
		}

		public static void ExtractNativeDependencies(string dest)
		{
			using(Stream? depStream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Mesen.Dependencies.zip")) {
				if(depStream == null) {
					throw new Exception("Missing dependencies.zip");
				}

				using ZipArchive zip = new(depStream);
				foreach(ZipArchiveEntry entry in zip.Entries) {
					try {
						if(entry.FullName.StartsWith("Internal")) {
							continue;
						}

						string path = Path.Combine(dest, entry.FullName);
						entry.ExternalAttributes = 0;
						if(File.Exists(path)) {
							if(Path.GetExtension(path)?.ToLower() == ".bin") {
								//Don't overwrite BS-X bin files if they already exist on the disk
								continue;
							}

							FileInfo fileInfo = new(path);
							if(fileInfo.LastWriteTime != entry.LastWriteTime || fileInfo.Length != entry.Length) {
								entry.ExtractToFile(path, true);
							}
						} else {
							string? folderName = Path.GetDirectoryName(path);
							if(folderName != null && !Directory.Exists(folderName)) {
								//Create any missing directory (e.g Satellaview)
								Directory.CreateDirectory(folderName);
							}
							entry.ExtractToFile(path, true);
						}
					} catch {

					}
				}
			}
		}

		private static IEnumerable<string> GetNativeLibrarySearchPaths(string libraryName)
		{
			List<string> paths = new();

			void AddFolder(string? folder)
			{
				if(string.IsNullOrWhiteSpace(folder)) {
					return;
				}

				string candidate = Path.Combine(folder, libraryName);
				if(!paths.Contains(candidate, StringComparer.OrdinalIgnoreCase)) {
					paths.Add(candidate);
				}
			}

			AddFolder(Path.GetDirectoryName(ExePath));
			AddFolder(AppContext.BaseDirectory);

			try { AddFolder(ConfigManager.HomeFolder); } catch { }
			try { AddFolder(ConfigManager.DefaultDocumentsFolder); } catch { }
			try { AddFolder(ConfigManager.DefaultPortableFolder); } catch { }

			try {
				string docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments, Environment.SpecialFolderOption.Create);
				AddFolder(Path.Combine(docs, "Mesen2"));
			} catch { }

			try {
				string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile, Environment.SpecialFolderOption.Create);
				AddFolder(Path.Combine(home, ".config", "Mesen2"));
			} catch { }

			return paths;
		}

		private static IntPtr DllImportResolver(string libraryName, Assembly assembly, DllImportSearchPath? searchPath)
		{
			if(libraryName.Contains("Mesen") || libraryName.Contains("SkiaSharp") || libraryName.Contains("HarfBuzz")) {
				if(libraryName.EndsWith(".dll")) {
					libraryName = libraryName.Substring(0, libraryName.Length - 4);
				}

				if(OperatingSystem.IsLinux()) {
					if(!libraryName.EndsWith(".so")) {
						libraryName = libraryName + ".so";
					}
				} else if(OperatingSystem.IsWindows()) {
					if(!libraryName.EndsWith(".dll")) {
						libraryName = libraryName + ".dll";
					}
				} else if(OperatingSystem.IsMacOS()) {
					if(!libraryName.EndsWith(".dylib")) {
						libraryName = libraryName + ".dylib";
					}
				}

				foreach(string path in GetNativeLibrarySearchPaths(libraryName)) {
					if(!File.Exists(path)) {
						continue;
					}
					try {
						return NativeLibrary.Load(path);
					} catch {
						//Try the next path.
					}
				}

				try {
					return NativeLibrary.Load(libraryName);
				} catch {
					return IntPtr.Zero;
				}
			}
			return IntPtr.Zero;
		}

		// Avalonia configuration, don't remove; also used by visual designer.
		public static AppBuilder BuildAvaloniaApp()
			 => AppBuilder.Configure<App>()
					.UseReactiveUI()
					.UsePlatformDetect()
					.With(new Win32PlatformOptions { })
					.With(new X11PlatformOptions { })
					.With(new AvaloniaNativePlatformOptions { })
					.LogToTrace();
	}
}
