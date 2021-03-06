package org.ovirt.engine.core.bll;

import javax.inject.Inject;

import org.ovirt.engine.core.bll.network.macpoolmanager.MacPoolPerDc;
import org.ovirt.engine.core.common.action.VdcActionParametersBase;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.dao.MacPoolDao;

public abstract class MacPoolCommandBase<T extends VdcActionParametersBase> extends CommandBase<T> {
    @Inject
    protected MacPoolPerDc poolPerDc;

    public MacPoolCommandBase(T parameters) {
        super(parameters);
    }

    @Override
    protected void setActionMessageParameters() {
        super.setActionMessageParameters();
        addValidationMessage(EngineMessage.VAR__TYPE__MAC__POOL);
    }

    public MacPoolDao getMacPoolDao() {
        return getDbFacade().getMacPoolDao();
    }
}
