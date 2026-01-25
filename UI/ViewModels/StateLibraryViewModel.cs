using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using Mesen.Utilities;
using Mesen.Interop;
using ReactiveUI;
using ReactiveUI.Fody.Helpers;

namespace Mesen.ViewModels
{
	public class StateLibraryViewModel : ViewModelBase
	{
		[Reactive] public ObservableCollection<OracleStateEntry> States { get; set; } = new();
		[Reactive] public OracleStateEntry? SelectedState { get; set; }

		public StateLibraryViewModel()
		{
			Refresh();
		}

		public void Refresh()
		{
			var manifest = OracleStateLibrary.GetManifest();
			States = new ObservableCollection<OracleStateEntry>(manifest.Entries);
		}

		public void LoadSelected()
		{
			if (SelectedState != null) {
				string path = OracleStateLibrary.GetFullPath(SelectedState);
				EmuApi.LoadStateFile(path);
			}
		}

		public void DeleteSelected()
		{
			if (SelectedState != null) {
				OracleStateLibrary.DeleteState(SelectedState.Id);
				Refresh();
			}
		}
	}
}
