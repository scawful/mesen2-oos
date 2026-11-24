using Avalonia.Threading;
using Mesen.Config;
using Mesen.Interop;
using Mesen.Utilities;
using ReactiveUI;
using ReactiveUI.Fody.Helpers;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace Mesen.ViewModels
{
	public class RomLibraryViewModel : ViewModelBase
	{
		private List<LibraryRom> _all = new();

		public ObservableCollection<object> Items { get; } = new();
		public ObservableCollection<LibrarySave> Saves { get; } = new();

		[Reactive] public string Filter { get; set; } = "";
		[Reactive] public object? SelectedItem { get; set; }
		[Reactive] public LibraryRom? Selected { get; private set; }
		[Reactive] public LibrarySave? SelectedSave { get; set; }
		[Reactive] public string Status { get; private set; } = "";
		[Reactive] public string FoldersText { get; private set; } = "";
		[Reactive] public bool HasSelection { get; private set; }
		[Reactive] public Avalonia.Media.Imaging.Bitmap? DetailPreview { get; private set; }
		[Reactive] public string PreviewCaption { get; private set; } = "";

		/// <summary>Set by the host window so Play can close it.</summary>
		public Action? AfterPlay { get; set; }

		public RomLibraryViewModel()
		{
			this.WhenAnyValue(x => x.Filter).Subscribe(_ => ApplyFilter());
			this.WhenAnyValue(x => x.SelectedSave, x => x.Selected).Subscribe(_ => {
				if(SelectedSave?.Preview is Avalonia.Media.Imaging.Bitmap savePreview) {
					DetailPreview = savePreview;
					PreviewCaption = SelectedSave.Label;
				} else {
					DetailPreview = Selected?.Preview;
					PreviewCaption = Selected?.Preview != null ? "Last seen in this build" : "";
				}
			});
			this.WhenAnyValue(x => x.SelectedItem).Subscribe(item => {
				Selected = item as LibraryRom;
				HasSelection = Selected != null;
				Saves.Clear();
				if(Selected != null) {
					foreach(LibrarySave save in RomLibrary.SavesFor(Selected)) {
						Saves.Add(save);
					}
					SelectedSave = Saves.FirstOrDefault();
				}
			});
		}

		public void Refresh()
		{
			string? keep = Selected?.RomPath;
			Status = "Scanning...";
			Task.Run(() => {
				List<LibraryRom> roms = RomLibrary.Scan();
				Dispatcher.UIThread.Post(() => {
					_all = roms;
					FoldersText = string.Join("  ·  ", RomLibrary.Folders.Select(f => f.Replace(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "~")));
					Status = $"{roms.Count} ROMs";
					ApplyFilter();
					SelectedItem = Items.OfType<LibraryRom>().FirstOrDefault(r => r.RomPath == keep) ?? Items.OfType<LibraryRom>().FirstOrDefault();
				});
			});
		}

		private void ApplyFilter()
		{
			object? keep = SelectedItem;
			Items.Clear();
			string f = Filter.Trim();
			string? group = null;
			foreach(LibraryRom rom in _all) {
				if(f.Length > 0 && !(rom.Title + " " + rom.Subtitle + " " + rom.Notes).Contains(f, StringComparison.OrdinalIgnoreCase)) {
					continue;
				}
				if(rom.Group != group) {
					group = rom.Group;
					Items.Add(group);
				}
				Items.Add(rom);
			}
			if(keep != null && Items.Contains(keep)) {
				SelectedItem = keep;
			}
		}

		public async void Play()
		{
			LibraryRom? rom = Selected;
			LibrarySave? save = SelectedSave;
			if(rom == null || save == null || !File.Exists(rom.RomPath)) {
				Status = rom == null ? "Pick a ROM." : "ROM file is missing.";
				return;
			}

			bool replacesSave = save.SourcePath != null || save.NewGame;
			Status = "Loading " + rom.Title + "...";
			if(EmuApi.IsRunning()) {
				await LibraryPreviews.CaptureCurrent(onlyGameplay: true);
			}
			_ = EmulatorOperationTracker.Run(token => {
				string? backup = null;
				string? error = null;
				if(replacesSave) {
					//Stop first so the running game's battery is written before the chosen save replaces it
					if(EmuApi.IsRunning()) {
						EmuApi.Stop();
					}
					token.ThrowIfCancellationRequested();
					try {
						backup = RomLibrary.ApplySave(rom, save);
					} catch(Exception ex) {
						error = ex.Message;
					}
				}
				Dispatcher.UIThread.Post(() => {
					if(error != null) {
						Status = "Could not set the save: " + error;
						return;
					}
					Status = backup != null ? "Previous save backed up to " + Path.GetFileName(backup) : "";
					LoadRomHelper.LoadFile(rom.RomPath);
					AfterPlay?.Invoke();
				});
			});
		}
	}
}
