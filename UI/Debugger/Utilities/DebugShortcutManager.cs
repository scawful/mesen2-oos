using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Rendering;
using Mesen.Config;
using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Mesen.Debugger.Utilities
{
	internal static class DebugShortcutManager
	{
		public static ContextMenu CreateContextMenu(Control ctrl, IEnumerable actions)
		{
			if(!(ctrl is IInputElement)) {
				throw new Exception("Invalid control");
			}

			ctrl.ContextMenu = new ContextMenu();
			ctrl.ContextMenu.Name = "ActionMenu";
			ctrl.ContextMenu.ItemsSource = actions;
			RegisterActions(ctrl, actions);

			return ctrl.ContextMenu;
		}

		public static void RegisterActions(IInputElement focusParent, IEnumerable actions)
		{
			foreach(object obj in actions) {
				if(obj is ContextMenuAction action) {
					RegisterAction(focusParent, action);
				}
			}
		}

		public static void RegisterActions(IInputElement focusParent, IEnumerable<ContextMenuAction> actions)
		{
			foreach(ContextMenuAction action in actions) {
				RegisterAction(focusParent, action);
			}
		}

		public static void RegisterAction(IInputElement focusParent, ContextMenuAction action)
		{
			WeakReference<IInputElement> weakFocusParent = new WeakReference<IInputElement>(focusParent);
			WeakReference<ContextMenuAction> weakAction = new WeakReference<ContextMenuAction>(action);

			if(action.SubActions != null) {
				RegisterActions(focusParent, action.SubActions);
			}

			EventHandler<KeyEventArgs>? handler = null;
			handler = (s, e) => {
				if(weakFocusParent.TryGetTarget(out IInputElement? elem)) {
					if(weakAction.TryGetTarget(out ContextMenuAction? act)) {
						if(act.Shortcut != null) {
							DbgShortKeys keys = act.Shortcut();
							if(IsShortcutMatch(e, keys)) {
								if(action.RoutingStrategy == RoutingStrategies.Bubble && e.Source is Control ctrl && ctrl.Classes.Contains("PreventShortcuts")) {
									return;
								}

								if(act.IsEnabled == null || act.IsEnabled()) {
									act.OnClick();
									e.Handled = true;
								}
							}
						}
					} else {
						elem.RemoveHandler(InputElement.KeyDownEvent, handler!);
					}
				}
			};

			focusParent.AddHandler(InputElement.KeyDownEvent, handler, action.RoutingStrategy, handledEventsToo: true);
		}

		private static bool IsShortcutMatch(KeyEventArgs e, DbgShortKeys keys)
		{
			if(e.Key == Key.None) {
				return false;
			}

			if(e.Key == keys.ShortcutKey && IsModifierMatch(e.KeyModifiers, keys.Modifiers)) {
				return true;
			}

			// macOS: Ctrl+M can arrive as Ctrl+Enter/Return in Avalonia, which breaks the default Debug shortcut.
			if(OperatingSystem.IsMacOS() && IsModifierMatch(e.KeyModifiers, KeyModifiers.Control)) {
				if(IsModifierMatch(keys.Modifiers, KeyModifiers.Control) && keys.ShortcutKey == Key.M && e.Key == Key.Enter) {
					return true;
				}
			}

			return false;
		}

		private static bool IsModifierMatch(KeyModifiers input, KeyModifiers expected)
		{
			if(input == expected) {
				return true;
			}

			if(!OperatingSystem.IsMacOS()) {
				return false;
			}

			if(!ConfigManager.Config.Debug.Debugger.UseCommandKeyForShortcuts) {
				return false;
			}

			return NormalizeCommandModifier(input) == NormalizeCommandModifier(expected);
		}

		private static KeyModifiers NormalizeCommandModifier(KeyModifiers modifiers)
		{
			if((modifiers & (KeyModifiers.Control | KeyModifiers.Meta)) != 0) {
				modifiers &= ~(KeyModifiers.Control | KeyModifiers.Meta);
				modifiers |= KeyModifiers.Control;
			}

			return modifiers;
		}
	}
}
