using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	public static class EmulatorOperationTracker
	{
		private static readonly object _operationLock = new();
		private static readonly HashSet<Task> _pendingOperations = new();
		private static readonly CancellationTokenSource _shutdownCts = new();
		private static bool _shutdownStarted;

		public static Task Run(Action<CancellationToken> operation)
		{
			ArgumentNullException.ThrowIfNull(operation);
			return Run(shutdownToken => {
				operation(shutdownToken);
				return Task.CompletedTask;
			});
		}

		public static Task Run(Func<CancellationToken, Task> operation)
		{
			ArgumentNullException.ThrowIfNull(operation);

			Task task;
			lock(_operationLock) {
				if(_shutdownStarted) {
					return Task.CompletedTask;
				}

				task = Task.Run(() => operation(_shutdownCts.Token), _shutdownCts.Token);
				_pendingOperations.Add(task);
			}

			_ = task.ContinueWith(completedTask => {
				lock(_operationLock) {
					_pendingOperations.Remove(completedTask);
				}
			}, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
			return task;
		}

		public static Task BeginShutdownAsync()
		{
			Task[] pendingOperations;
			bool cancelOperations;
			lock(_operationLock) {
				cancelOperations = !_shutdownStarted;
				_shutdownStarted = true;
				pendingOperations = _pendingOperations.ToArray();
			}

			if(cancelOperations) {
				_shutdownCts.Cancel();
			}
			return Task.WhenAll(pendingOperations);
		}
	}
}
