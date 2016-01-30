package org.ovirt.engine.core.bll;

import java.util.*;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.bll.CommandActionState;

public privileged aspect Logs {
	AuditLogType around(org.ovirt.engine.core.bll.MigrateVmCommand command): execution(* getAuditLogTypeValue()) && this(command) {
		if (command.getSucceeded()
		 && command.isReturnValueUp())
			return AuditLogType.VM_MIGRATION_DONE;
		if (command.getSucceeded() && command.isInternalExecution()
		)
			return AuditLogType.VM_MIGRATION_START_SYSTEM_INITIATED;
		if (command.getSucceeded()
		)
			return AuditLogType.VM_MIGRATION_START;
		if (!command.getSucceeded()
		 && command.isHostInPrepareForMaintenance())
			return AuditLogType.VM_MIGRATION_FAILED_DURING_MOVE_TO_MAINTENANCE;
		if (!command.getSucceeded()
		)
			return AuditLogType.VM_MIGRATION_FAILED;
		return AuditLogType.UNASSIGNED;
	}
	
	AuditLogType around(org.ovirt.engine.core.bll.storage.disk.AddDiskCommand command): execution(* getAuditLogTypeValue()) && this(command) {
		if (command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE && command.isInternalExecution()
		 && command.isDiskStorageTypeRequiresExecuteState())
			return AuditLogType.ADD_DISK_INTERNAL;
		if (command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE
		 && command.isDiskStorageTypeRequiresExecuteState() && command.isVmNameExists())
			return AuditLogType.USER_ADD_DISK_TO_VM;
		if (command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE
		 && command.isDiskStorageTypeRequiresExecuteState())
			return AuditLogType.USER_ADD_DISK;
		if (!command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE && command.isInternalExecution()
		 && command.isDiskStorageTypeRequiresExecuteState())
			return AuditLogType.ADD_DISK_INTERNAL_FAILURE;
		if (!command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE
		 && command.isDiskStorageTypeRequiresExecuteState() && command.isVmNameExists())
			return AuditLogType.USER_FAILED_ADD_DISK_TO_VM;
		if (!command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE
		 && command.isDiskStorageTypeRequiresExecuteState())
			return AuditLogType.USER_FAILED_ADD_DISK;
		if (command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE
		 && command.isVmNameExists())
			return AuditLogType.USER_ADD_DISK_TO_VM_FINISHED_SUCCESS;
		if (command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE
		)
			return AuditLogType.USER_ADD_DISK_FINISHED_SUCCESS;
		if (!command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE
		 && command.isVmNameExists())
			return AuditLogType.USER_ADD_DISK_TO_VM_FINISHED_FAILURE;
		if (!command.getSucceeded() && command.getActionState() == CommandActionState.EXECUTE
		)
			return AuditLogType.USER_ADD_DISK_FINISHED_FAILURE;
		if (command.getSucceeded() && command.getActionState() == CommandActionState.END_SUCCESS
		 && command.isVmNameExists())
			return AuditLogType.USER_ADD_DISK_TO_VM_FINISHED_SUCCESS;
		if (command.getSucceeded() && command.getActionState() == CommandActionState.END_SUCCESS
		)
			return AuditLogType.USER_ADD_DISK_FINISHED_SUCCESS;
		if (!command.getSucceeded() && command.getActionState() == CommandActionState.END_SUCCESS
		 && command.isVmNameExists())
			return AuditLogType.USER_ADD_DISK_TO_VM_FINISHED_FAILURE;
		if (!command.getSucceeded() && command.getActionState() == CommandActionState.END_SUCCESS
		)
			return AuditLogType.USER_ADD_DISK_FINISHED_FAILURE;
		return AuditLogType.USER_ADD_DISK_FINISHED_FAILURE;
	}
	
}

