package org.ovirt.engine.core.bll.storage.connection.iscsibond;

import org.ovirt.engine.core.bll.validator.IscsiBondValidator;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.action.RemoveIscsiBondParameters;
import org.ovirt.engine.core.common.businessentities.IscsiBond;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.compat.Guid;

public class RemoveIscsiBondCommand<T extends RemoveIscsiBondParameters> extends BaseIscsiBondCommand<T> {

    private IscsiBond iscsiBond;

    public RemoveIscsiBondCommand(T parameters) {
        super(parameters);
    }

    @Override
    protected boolean validate() {
        IscsiBondValidator validator = new IscsiBondValidator();
        return validate(validator.isIscsiBondExist(getIscsiBond()));
    }

    @Override
    protected void executeCommand() {
        getDbFacade().getIscsiBondDao().remove(getParameters().getIscsiBondId());
        setSucceeded(true);
    }

    @Override
    public Guid getStoragePoolId() {
        Guid storagePoolId = super.getStoragePoolId();

        if (storagePoolId == null) {
            IscsiBond iscsiBond = getIscsiBond();

            if (iscsiBond != null) {
                storagePoolId = iscsiBond.getStoragePoolId();
                setStoragePoolId(storagePoolId);
            }
        }

        return storagePoolId;
    }

    @Override
    protected IscsiBond getIscsiBond() {
        if (iscsiBond == null) {
            iscsiBond = getDbFacade().getIscsiBondDao().get(getParameters().getIscsiBondId());
        }

        return iscsiBond;
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        return getSucceeded() ? AuditLogType.ISCSI_BOND_REMOVE_SUCCESS : AuditLogType.ISCSI_BOND_REMOVE_FAILED;
    }

    @Override
    protected void setActionMessageParameters() {
        addValidationMessage(EngineMessage.VAR__ACTION__REMOVE);
        addValidationMessage(EngineMessage.VAR__TYPE__ISCSI_BOND);
    }
}
