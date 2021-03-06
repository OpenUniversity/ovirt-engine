package org.ovirt.engine.core.bll.tasks;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import org.ovirt.engine.core.bll.Backend;
import org.ovirt.engine.core.bll.CommandBase;
import org.ovirt.engine.core.bll.CommandsFactory;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.context.EngineContext;
import org.ovirt.engine.core.bll.interfaces.BackendInternal;
import org.ovirt.engine.core.bll.job.ExecutionContext;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandContextsCache;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandCoordinator;
import org.ovirt.engine.core.bll.tasks.interfaces.SPMTask;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.VdcActionParametersBase;
import org.ovirt.engine.core.common.action.VdcActionType;
import org.ovirt.engine.core.common.action.VdcReturnValueBase;
import org.ovirt.engine.core.common.asynctasks.AsyncTaskCreationInfo;
import org.ovirt.engine.core.common.asynctasks.AsyncTaskParameters;
import org.ovirt.engine.core.common.asynctasks.AsyncTaskType;
import org.ovirt.engine.core.common.businessentities.AsyncTask;
import org.ovirt.engine.core.common.businessentities.AsyncTaskStatus;
import org.ovirt.engine.core.common.businessentities.CommandAssociatedEntity;
import org.ovirt.engine.core.common.businessentities.CommandEntity;
import org.ovirt.engine.core.common.businessentities.SubjectEntity;
import org.ovirt.engine.core.common.vdscommands.IrsBaseVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.SPMTaskGuidBaseVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSParametersBase;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.compat.CommandStatus;
import org.ovirt.engine.core.compat.DateTime;
import org.ovirt.engine.core.compat.Guid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandCoordinatorImpl extends CommandCoordinator {

    private static final Logger log = LoggerFactory.getLogger(CommandCoordinator.class);
    private final CommandsCache commandsCache;
    private final CommandContextsCache contextsCache;
    private final CoCoAsyncTaskHelper coCoAsyncTaskHelper;
    private final CommandExecutor cmdExecutor;
    private Object LOCK = new Object();
    private volatile boolean childHierarchyInitialized;
    private ConcurrentHashMap<Guid, List<Guid>> childHierarchy = new ConcurrentHashMap<>();

    CommandCoordinatorImpl() {
        commandsCache = new CommandsCacheImpl();
        contextsCache = new CommandContextsCacheImpl(commandsCache);
        coCoAsyncTaskHelper = new CoCoAsyncTaskHelper(this);
        cmdExecutor = new CommandExecutor(this);
    }

    public <P extends VdcActionParametersBase> CommandBase<P> createCommand(VdcActionType action, P parameters) {
        return CommandsFactory.createCommand(action, parameters);
    }

    @Override
    public void persistCommand(CommandEntity cmdEntity, CommandContext cmdContext) {
        initChildHierarchy();
        if (Guid.isNullOrEmpty(cmdEntity.getId())) {
            return;
        }
        persistCommand(cmdEntity);
        saveCommandContext(cmdEntity.getId(), cmdContext);
    }

    @Override
    public void persistCommand(CommandEntity cmdEntity) {
        if (Guid.isNullOrEmpty(cmdEntity.getId())) {
            return;
        }
        CommandEntity existingCmdEntity = commandsCache.get(cmdEntity.getId());
        if (existingCmdEntity != null) {
            cmdEntity.setExecuted(existingCmdEntity.isExecuted());
            cmdEntity.setCallbackNotified(existingCmdEntity.isCallbackNotified());
        }
        commandsCache.put(cmdEntity);
        // check if callback is enabled or if parent command has callback enabled
        if (cmdEntity.isCallbackEnabled() ||
                (!Guid.isNullOrEmpty(cmdEntity.getParentCommandId()) &&
                        commandsCache.get(cmdEntity.getParentCommandId()) != null &&
                        commandsCache.get(cmdEntity.getParentCommandId()).isCallbackEnabled()
                )) {
            buildCmdHierarchy(cmdEntity);
            if (!cmdEntity.isCallbackNotified()) {
                cmdExecutor.addToCallbackMap(cmdEntity);
            }
        }
    }

    @Override
    public void persistCommandAssociatedEntities(Collection<CommandAssociatedEntity> cmdAssociatedEntities) {
        commandsCache.persistCommandAssociatedEntities(cmdAssociatedEntities);
    }

    @Override
    public List<Guid> getCommandIdsByEntityId(Guid entityId) {
        return commandsCache.getCommandIdsByEntityId(entityId);
    }

    @Override
    public List<CommandAssociatedEntity> getCommandAssociatedEntities(Guid cmdId) {
        return commandsCache.getCommandAssociatedEntities(cmdId);
    }

    void saveCommandContext(Guid cmdId, CommandContext cmdContext) {
        if (cmdContext != null) {
            contextsCache.put(cmdId, cmdContext);
        }
    }

    /**
     * Executes the action using a Thread Pool. Used when the calling function
     * would like the execute the command with no delay
     */
    @Override
    public Future<VdcReturnValueBase> executeAsyncCommand(VdcActionType actionType,
                                                          VdcActionParametersBase parameters,
                                                          CommandContext cmdContext,
                                                          SubjectEntity... subjectEntities) {
        return cmdExecutor.executeAsyncCommand(actionType, parameters, cmdContext, subjectEntities);
    }

    @Override
    public CommandEntity getCommandEntity(Guid commandId) {
        return Guid.isNullOrEmpty(commandId) ? null : commandsCache.get(commandId);
    }

    @Override
    public CommandEntity createCommandEntity(Guid cmdId, VdcActionType actionType, VdcActionParametersBase params) {
        CommandEntity cmdEntity = new CommandEntity();
        cmdEntity.setId(cmdId);
        cmdEntity.setCommandType(actionType);
        cmdEntity.setCommandParameters(params);
        return cmdEntity;
    }

    @Override
    public List<CommandEntity> getCommandsWithCallbackEnabled() {
        return getCommands(true);
    }


    public List<CommandEntity> getCommands(boolean onlyWithCallbackEnabled) {
        List<CommandEntity> cmdEntities = new ArrayList<>();
        CommandEntity cmdEntity;
        for (Guid cmdId : commandsCache.keySet()) {
            cmdEntity = commandsCache.get(cmdId);
            if (!onlyWithCallbackEnabled || commandsCache.get(cmdId).isCallbackEnabled()) {
                cmdEntities.add(cmdEntity);
            }
        }
        return cmdEntities;
    }

    @Override
    public CommandBase<?> retrieveCommand(Guid commandId) {
        return buildCommand(commandsCache.get(commandId), contextsCache.get(commandId));
    }

    private CommandBase<?> buildCommand(CommandEntity cmdEntity, CommandContext cmdContext) {
        CommandBase<?> command = null;
        if (cmdEntity != null) {
            if (cmdContext == null) {
                cmdContext = new CommandContext(new EngineContext()).withExecutionContext(new ExecutionContext());
            }
            command = CommandsFactory.createCommand(cmdEntity.getCommandType(), cmdEntity.getCommandParameters(), cmdContext);
            command.setCommandStatus(cmdEntity.getCommandStatus(), false);
            command.setCommandData(cmdEntity.getData());
            if (!Guid.isNullOrEmpty(cmdEntity.getParentCommandId()) &&
                    ! cmdEntity.getParentCommandId().equals(cmdEntity.getId()) &&
                    command.getParameters().getParentParameters() == null) {
                CommandBase<?> parentCommand = retrieveCommand(cmdEntity.getParentCommandId());
                if (parentCommand != null) {
                    command.getParameters().setParentParameters(parentCommand.getParameters());
                }
            }
        }
        return command;
    }

    @Override
    public CommandStatus getCommandStatus(final Guid commandId) {
        CommandEntity cmdEntity = commandsCache.get(commandId);
        if (cmdEntity != null) {
            return cmdEntity.getCommandStatus();
        }
        return CommandStatus.UNKNOWN;
    }

    @Override
    public void removeAllCommandsInHierarchy(final Guid commandId) {
        for (Guid childCmdId : new ArrayList<>(getChildCommandIds(commandId))) {
            removeAllCommandsInHierarchy(childCmdId);
        }
        removeCommand(commandId);
    }

    @Override
    public void removeCommand(final Guid commandId) {
        commandsCache.remove(commandId);
        contextsCache.remove(commandId);
        updateCmdHierarchy(commandId);
    }

    @Override
    public void removeAllCommandsBeforeDate(final DateTime cutoff) {
        commandsCache.removeAllCommandsBeforeDate(cutoff);
        synchronized(LOCK) {
            childHierarchyInitialized = false;
        }
    }

    @Override
    public void updateCommandData(final Guid commandId, final Map<String, Serializable> data) {
        commandsCache.updateCommandData(commandId, data);
    }

    @Override
    public void updateCommandStatus(final Guid commandId, final CommandStatus status) {
        commandsCache.updateCommandStatus(commandId, status);
    }

    @Override
    public void updateCommandExecuted(Guid commandId) {
        commandsCache.updateCommandExecuted(commandId);
    }

    @Override
    public void updateCallbackNotified(final Guid commandId) {
        commandsCache.updateCallbackNotified(commandId);
    }

    @Override
    public boolean hasCommandEntitiesWithRootCommandId(Guid rootCommandId) {
        CommandEntity cmdEntity;
        for (Guid cmdId : commandsCache.keySet()) {
            cmdEntity = commandsCache.get(cmdId);
            if (cmdEntity != null && !Guid.isNullOrEmpty(cmdEntity.getRootCommandId()) &&
                    !cmdEntity.getRootCommandId().equals(cmdId) &&
                    cmdEntity.getRootCommandId().equals(rootCommandId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Guid> getChildCommandIds(Guid cmdId) {
        initChildHierarchy();
        if (childHierarchy.containsKey(cmdId)) {
            return childHierarchy.get(cmdId);
        }
        return Collections.emptyList();
    }

    @Override
    public List<Guid> getChildCommandIds(Guid cmdId, VdcActionType childActionType, CommandStatus status) {
        List<Guid> childCmdIds = new ArrayList<>();
        for (Guid childCmdId : getChildCommandIds(cmdId)) {
            CommandEntity childCmdEntity = getCommandEntity(childCmdId);
            if (childCmdEntity != null &&
                    childCmdEntity.getCommandType().equals(childActionType) &&
                    (status == null || status.equals(childCmdEntity.getCommandStatus()))) {
                childCmdIds.add(childCmdId);
            }
        }
        return childCmdIds;
    }

    @Override
    public List<CommandEntity> getChildCmdsByRootCmdId(Guid cmdId) {
        return commandsCache.getChildCmdsByParentCmdId(cmdId);
    }

    private void initChildHierarchy() {
        if (!childHierarchyInitialized) {
            synchronized(LOCK) {
                if (!childHierarchyInitialized) {
                    childHierarchy.clear();
                    for (CommandEntity cmd : getCommands(false)) {
                        buildCmdHierarchy(cmd);
                    }
                }
                childHierarchyInitialized = true;
            }
        }
    }

    private void buildCmdHierarchy(CommandEntity cmdEntity) {
        if (!Guid.isNullOrEmpty(cmdEntity.getParentCommandId()) && !cmdEntity.getId().equals(cmdEntity.getParentCommandId())) {
            childHierarchy.putIfAbsent(cmdEntity.getParentCommandId(), new ArrayList<>());
            if (!childHierarchy.get(cmdEntity.getParentCommandId()).contains(cmdEntity.getId())) {
                childHierarchy.get(cmdEntity.getParentCommandId()).add(cmdEntity.getId());
            }
        }
    }

    private void updateCmdHierarchy(Guid cmdId) {
        for (List<Guid> childIds : childHierarchy.values()) {
            if (childIds.contains(cmdId)) {
                childIds.remove(cmdId);
                break;
            }
        }
        if (childHierarchy.containsKey(cmdId) && childHierarchy.get(cmdId).size() == 0) {
            childHierarchy.remove(cmdId);
        }
    }

    @Override
    public List<AsyncTask> getAllAsyncTasksFromDb() {
        return coCoAsyncTaskHelper.getAllAsyncTasksFromDb(this);
    }

    @Override
    public void saveAsyncTaskToDb(final AsyncTask asyncTask) {
        coCoAsyncTaskHelper.saveAsyncTaskToDb(asyncTask);
    }

    @Override
    public AsyncTask getAsyncTaskFromDb(Guid asyncTaskId) {
        return coCoAsyncTaskHelper.getAsyncTaskFromDb(asyncTaskId);
    }

    @Override
    public int removeTaskFromDbByTaskId(final Guid taskId) throws RuntimeException {
        return coCoAsyncTaskHelper.removeTaskFromDbByTaskId(taskId);
    }

    @Override
    public AsyncTask getByVdsmTaskId(Guid vdsmTaskId) {
        return coCoAsyncTaskHelper.getByVdsmTaskId(vdsmTaskId);
    }

    @Override
    public int removeByVdsmTaskId(final Guid vdsmTaskId) {
        return coCoAsyncTaskHelper.removeByVdsmTaskId(vdsmTaskId);
    }

    @Override
    public void addOrUpdateTaskInDB(final AsyncTask asyncTask) {
        coCoAsyncTaskHelper.addOrUpdateTaskInDB(asyncTask);
    }

    public SPMAsyncTask createTask(AsyncTaskType taskType, AsyncTaskParameters taskParameters) {
        return coCoAsyncTaskHelper.createTask(taskType, taskParameters);
    }

    @Override
    public AsyncTask getAsyncTask(
            Guid taskId,
            CommandBase<?> command,
            AsyncTaskCreationInfo asyncTaskCreationInfo,
            VdcActionType parentCommand) {
        return coCoAsyncTaskHelper.getAsyncTask(taskId, command, asyncTaskCreationInfo, parentCommand);
    }

    @Override
    public AsyncTask createAsyncTask(
            CommandBase<?> command,
            AsyncTaskCreationInfo asyncTaskCreationInfo,
            VdcActionType parentCommand) {
        return coCoAsyncTaskHelper.createAsyncTask(command, asyncTaskCreationInfo, parentCommand);
    }

    @Override
    public Guid createTask(Guid taskId,
            CommandBase<?> command,
                           AsyncTaskCreationInfo asyncTaskCreationInfo,
                           VdcActionType parentCommand,
                           String description,
                           Map<Guid, VdcObjectType> entitiesMap) {
        return coCoAsyncTaskHelper.createTask(taskId, command, asyncTaskCreationInfo, parentCommand, description, entitiesMap);

    }

    @Override
    public SPMAsyncTask concreteCreateTask(
            Guid taskId,
            CommandBase<?> command,
            AsyncTaskCreationInfo asyncTaskCreationInfo,
            VdcActionType parentCommand) {
        return coCoAsyncTaskHelper.concreteCreateTask(taskId, command, asyncTaskCreationInfo, parentCommand);
    }

    @Override
    public void cancelTasks(final CommandBase<?> command) {
        coCoAsyncTaskHelper.cancelTasks(command, log);
    }

    @Override
    public void revertTasks(CommandBase<?> command) {
        coCoAsyncTaskHelper.revertTasks(command);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ArrayList<AsyncTaskCreationInfo> getAllTasksInfo(Guid storagePoolID) {
        return (ArrayList<AsyncTaskCreationInfo>) runVdsCommand(VDSCommandType.SPMGetAllTasksInfo,
                new IrsBaseVDSCommandParameters(storagePoolID)).getReturnValue();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<Guid, AsyncTaskStatus>  getAllTasksStatuses(Guid storagePoolID) {
        return (Map<Guid, AsyncTaskStatus> ) runVdsCommand(VDSCommandType.SPMGetAllTasksStatuses,
                new IrsBaseVDSCommandParameters(storagePoolID)).getReturnValue();
    }

    @Override
    public void stopTask(Guid storagePoolID, Guid vdsmTaskID) {
        runVdsCommand(VDSCommandType.SPMStopTask,
                new SPMTaskGuidBaseVDSCommandParameters(storagePoolID, vdsmTaskID));
    }

    @Override
    public VDSReturnValue clearTask(Guid storagePoolID, Guid vdsmTaskID) {
        return runVdsCommand(VDSCommandType.SPMClearTask,
                new SPMTaskGuidBaseVDSCommandParameters(storagePoolID, vdsmTaskID));
    }

    @Override
    public boolean doesCommandContainAsyncTask(Guid cmdId) {
        return AsyncTaskManager.getInstance().doesCommandContainAsyncTask(cmdId);
    }

    private VDSReturnValue runVdsCommand(VDSCommandType commandType, VDSParametersBase parameters) {
        return Backend.getInstance().getResourceManager().runVdsCommand(commandType, parameters);
    }

    @Override
    public SPMTask construct(AsyncTaskCreationInfo creationInfo) {
        return AsyncTaskFactory.construct(this, creationInfo);
    }

    @Override
    public SPMTask construct(AsyncTaskCreationInfo creationInfo, AsyncTask asyncTask) {
        return AsyncTaskFactory.construct(this, creationInfo.getTaskType(), new AsyncTaskParameters(creationInfo, asyncTask), true);
    }

    @Override
    public SPMTask construct(AsyncTaskType taskType, AsyncTaskParameters asyncTaskParams, boolean duringInit) {
        return AsyncTaskFactory.construct(this, taskType, asyncTaskParams, duringInit);
    }

    @Override
    public VdcReturnValueBase endAction(SPMTask task, ExecutionContext context) {
        return coCoAsyncTaskHelper.endAction(task, context);
    }

    protected BackendInternal getBackend() {
        return Backend.getInstance();
    }

}
