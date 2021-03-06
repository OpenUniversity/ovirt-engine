package org.ovirt.engine.core.bll.storage.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.bll.NonTransactiveCommandAttribute;
import org.ovirt.engine.core.bll.RenamedEntityInfoProvider;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.network.cluster.ManagementNetworkUtil;
import org.ovirt.engine.core.bll.network.macpoolmanager.MacPoolManagerStrategy;
import org.ovirt.engine.core.bll.network.macpoolmanager.MacPoolPerDc;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.bll.utils.VersionSupport;
import org.ovirt.engine.core.bll.validator.NetworkValidator;
import org.ovirt.engine.core.bll.validator.storage.StorageDomainToPoolRelationValidator;
import org.ovirt.engine.core.bll.validator.storage.StoragePoolValidator;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.FeatureSupported;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.StoragePoolManagementParameter;
import org.ovirt.engine.core.common.businessentities.ActionGroup;
import org.ovirt.engine.core.common.businessentities.Cluster;
import org.ovirt.engine.core.common.businessentities.StorageDomain;
import org.ovirt.engine.core.common.businessentities.StorageDomainStatic;
import org.ovirt.engine.core.common.businessentities.StorageDomainType;
import org.ovirt.engine.core.common.businessentities.StorageFormatType;
import org.ovirt.engine.core.common.businessentities.StoragePool;
import org.ovirt.engine.core.common.businessentities.StoragePoolStatus;
import org.ovirt.engine.core.common.businessentities.network.Network;
import org.ovirt.engine.core.common.errors.EngineError;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.utils.VersionStorageFormatUtil;
import org.ovirt.engine.core.common.vdscommands.UpgradeStoragePoolVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.compat.TransactionScopeOption;
import org.ovirt.engine.core.compat.Version;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogableBase;
import org.ovirt.engine.core.dao.StorageDomainStaticDao;
import org.ovirt.engine.core.dao.VmDao;
import org.ovirt.engine.core.dao.network.NetworkDao;
import org.ovirt.engine.core.dao.network.VmNicDao;
import org.ovirt.engine.core.utils.ReplacementUtils;
import org.ovirt.engine.core.utils.transaction.TransactionSupport;

@NonTransactiveCommandAttribute
public class UpdateStoragePoolCommand<T extends StoragePoolManagementParameter> extends
        StoragePoolManagementCommandBase<T>  implements RenamedEntityInfoProvider{

    @Inject
    private ManagementNetworkUtil managementNetworkUtil;

    @Inject
    private VmDao vmDao;

    @Inject VmNicDao vmNicDao;

    @Inject
    private MacPoolPerDc poolPerDc;

    public UpdateStoragePoolCommand(T parameters) {
        this(parameters, null);
    }

    /**
     * Constructor for command creation when compensation is applied on startup
     */
    public UpdateStoragePoolCommand(Guid commandId) {
        super(commandId);
    }

    public UpdateStoragePoolCommand(T parameters, CommandContext commandContext) {
        super(parameters, commandContext);
    }

    private StoragePool oldStoragePool;
    private StorageDomain masterDomainForPool;

    @Override
    protected void executeCommand() {
        Guid oldMacPoolId = getOldMacPoolId();
        Guid newMacPoolId = getNewMacPoolId();
        Objects.requireNonNull(oldMacPoolId); //this should not happen, just make sure this invariant is fulfilled.
        Objects.requireNonNull(newMacPoolId); //this should not happen, just make sure this invariant is fulfilled.
        boolean needToMigrateMacs = !oldMacPoolId.equals(newMacPoolId);



        updateQuotaCache();
        copyUnchangedStoragePoolProperties(getStoragePool(), oldStoragePool);

        List<String> vmInterfaceMacs = null;
        if (needToMigrateMacs) {
            vmInterfaceMacs = vmNicDao.getAllMacsByDataCenter(getParameters().getStoragePoolId());
        }

        getStoragePoolDao().updatePartial(getStoragePool());

        if (needToMigrateMacs) {
            moveMacsOfUpdatedDataCenter(oldMacPoolId, newMacPoolId, vmInterfaceMacs);
        }

        updateStoragePoolFormatType();

        setSucceeded(true);
    }

    /**
     * All MACs of given DC are found, and all of them are {@link MacPoolManagerStrategy#freeMac(String) freed}
     * from source {@link MacPoolManagerStrategy macPool} and are
     * {@link MacPoolManagerStrategy#forceAddMac(String) added}
     * to target {@link MacPoolManagerStrategy macPool}. Because source macPool may contain duplicates and/or allow
     * duplicates, {@link MacPoolManagerStrategy#forceAddMac(String)} is used to add them override
     * <em>allowDuplicates</em> setting of target macPool.
     * @param oldMacPoolId id of macPool before update
     * @param newMacPoolId macPool Id of updated data center.
     */
    private void moveMacsOfUpdatedDataCenter(Guid oldMacPoolId, Guid newMacPoolId, List<String> vmInterfaceMacs) {
        Objects.requireNonNull(vmInterfaceMacs);

        MacPoolManagerStrategy sourcePool = poolPerDc.getPoolById(oldMacPoolId);
        MacPoolManagerStrategy targetPool = poolPerDc.getPoolById(newMacPoolId);

        for (String mac : vmInterfaceMacs) {
            sourcePool.freeMac(mac);
            targetPool.forceAddMac(mac);
        }
    }

    private void updateQuotaCache() {
        if(wasQuotaEnforcementChanged()){
            getQuotaManager().removeStoragePoolFromCache(getStoragePool().getId());
        }
    }

    /**
     * Checks whether part of the update was disabling quota enforcement on the Data Center
     */
    private boolean wasQuotaEnforcementChanged() {
        return getOldStoragePool().getQuotaEnforcementType() != getStoragePool().getQuotaEnforcementType();
    }

    private StorageFormatType updatePoolAndDomainsFormat(final Version spVersion) {
        final StoragePool storagePool = getStoragePool();

        final StorageFormatType targetFormat =
                VersionStorageFormatUtil.getPreferredForVersion(spVersion, getMasterDomain() == null ? null : getMasterDomain().getStorageType());

        storagePool.setCompatibilityVersion(spVersion);
        storagePool.setStoragePoolFormatType(targetFormat);

        TransactionSupport.executeInScope(TransactionScopeOption.RequiresNew,
                () -> {
                    getStoragePoolDao().updatePartial(storagePool);
                    updateMemberDomainsFormat(targetFormat);
                    if (FeatureSupported.ovfStoreOnAnyDomain(spVersion)) {
                        getVmStaticDao().incrementDbGenerationForAllInStoragePool(storagePool.getId());
                    }
                    return null;
                });

        return targetFormat;
    }

    private void updateStoragePoolFormatType() {
        final StoragePool storagePool = getStoragePool();
        final Guid spId = storagePool.getId();
        final Version spVersion = storagePool.getCompatibilityVersion();
        final Version oldSpVersion = getOldStoragePool().getCompatibilityVersion();

        if (oldSpVersion.equals(spVersion)) {
            return;
        }

        StorageFormatType targetFormat = updatePoolAndDomainsFormat(spVersion);

        if (getOldStoragePool().getStatus() == StoragePoolStatus.Up) {
            try {
                // No need to worry about "reupgrading" as VDSM will silently ignore
                // the request.
                runVdsCommand(VDSCommandType.UpgradeStoragePool,
                    new UpgradeStoragePoolVDSCommandParameters(spId, targetFormat));
            } catch (EngineException e) {
                log.warn("Upgrade process of Storage Pool '{}' has encountered a problem due to following reason: {}",
                        spId, e.getMessage());
                auditLogDirector.log(this, AuditLogType.UPGRADE_STORAGE_POOL_ENCOUNTERED_PROBLEMS);

                // if we get this error we know that no update was made, so we can safely revert the db updates
                // and return.
                if (e.getVdsError() != null && e.getErrorCode() == EngineError.PoolUpgradeInProgress) {
                    updatePoolAndDomainsFormat(oldSpVersion);
                    return;
                }
            }
        }

        runSynchronizeOperation(new RefreshPoolSingleAsyncOperationFactory(), new ArrayList<Guid>());
    }

    private void updateMemberDomainsFormat(StorageFormatType targetFormat) {
        Guid spId = getStoragePool().getId();
        StorageDomainStaticDao sdStatDao = DbFacade.getInstance().getStorageDomainStaticDao();
        List<StorageDomainStatic> domains = sdStatDao.getAllForStoragePool(spId);
        for (StorageDomainStatic domain : domains) {
            StorageDomainType sdType = domain.getStorageDomainType();

            if (sdType == StorageDomainType.Data || sdType == StorageDomainType.Master) {
                log.info("Setting storage domain '{}' (type '{}') to format '{}'",
                               domain.getId(), sdType, targetFormat);

                domain.setStorageFormat(targetFormat);
                sdStatDao.update(domain);
            }
        }
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        return getSucceeded() ? AuditLogType.USER_UPDATE_STORAGE_POOL : AuditLogType.USER_UPDATE_STORAGE_POOL_FAILED;
    }

    @Override
    protected void setActionMessageParameters() {
        super.setActionMessageParameters();
        addValidationMessage(EngineMessage.VAR__ACTION__UPDATE);
    }

    @Override
    protected boolean validate() {
        if (!checkStoragePool()) {
            return false;
        }

        // Name related validations
        if (!StringUtils.equals(getOldStoragePool().getName(), getStoragePool().getName())
                && !isStoragePoolUnique(getStoragePool().getName())) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_STORAGE_POOL_NAME_ALREADY_EXIST);
        }
        if (!checkStoragePoolNameLengthValid()) {
            return false;
        }

        List<StorageDomainStatic> poolDomains = getStorageDomainStaticDao().getAllForStoragePool(getStoragePool().getId());
        if ( getOldStoragePool().isLocal() != getStoragePool().isLocal() && !poolDomains.isEmpty() ) {
            return failValidation(EngineMessage.ERROR_CANNOT_CHANGE_STORAGE_POOL_TYPE_WITH_DOMAINS);
        }
        if ( !getOldStoragePool().getCompatibilityVersion().equals(getStoragePool()
                .getCompatibilityVersion())) {
            if (!isStoragePoolVersionSupported()) {
                return failValidation(VersionSupport.getUnsupportedVersionMessage());
            }
            // decreasing of compatibility version is allowed under conditions
            else if (getStoragePool().getCompatibilityVersion().compareTo(getOldStoragePool().getCompatibilityVersion()) < 0) {
                if (!poolDomains.isEmpty() && !isCompatibilityVersionChangeAllowedForDomains(poolDomains)) {
                    return false;
                }
                List<Network> networks = getNetworkDao().getAllForDataCenter(getStoragePoolId());
                for (Network network : networks) {
                    NetworkValidator validator = getNetworkValidator(network);
                    validator.setDataCenter(getStoragePool());
                    if (!getManagementNetworkUtil().isManagementNetwork(network.getId())
                            || !validator.canNetworkCompatabilityBeDecreased()) {
                        return failValidation(EngineMessage.ACTION_TYPE_FAILED_CANNOT_DECREASE_COMPATIBILITY_VERSION);
                    }
                }
            } else if (!checkAllClustersLevel()) {  // Check all clusters has at least the same compatibility version.
                return false;
            }
        }

        StoragePoolValidator validator = createStoragePoolValidator();
        return validate(validator.isNotLocalfsWithDefaultCluster());
    }

    private boolean isCompatibilityVersionChangeAllowedForDomains(List<StorageDomainStatic> poolDomains) {
        List<String> formatProblematicDomains = new ArrayList<>();
        List<String> typeProblematicDomains = new ArrayList<>();
        boolean failOnSupportedTypeMixing = false;

        for (StorageDomainStatic domainStatic : poolDomains) {
            StorageDomainToPoolRelationValidator attachDomainValidator = getAttachDomainValidator(domainStatic);

            if (!failOnSupportedTypeMixing && !attachDomainValidator.isStorageDomainTypeFitsPoolIfMixed().isValid()) {
                failOnSupportedTypeMixing = true;
            }
            if (!attachDomainValidator.isStorageDomainTypeSupportedInPool().isValid()) {
                typeProblematicDomains.add(domainStatic.getName());
            }
            if (!attachDomainValidator.isStorageDomainFormatCorrectForDC().isValid()) {
                formatProblematicDomains.add(domainStatic.getName());
            }
        }

        return manageCompatibilityVersionChangeCheckResult(failOnSupportedTypeMixing,
                formatProblematicDomains,
                typeProblematicDomains);
    }

    private boolean manageCompatibilityVersionChangeCheckResult(boolean failOnSupportedTypeMixing, List<String> formatProblematicDomains, List<String> typeProblematicDomains) {
        if (failOnSupportedTypeMixing) {
            addValidationMessage(EngineMessage.ACTION_TYPE_FAILED_MIXED_STORAGE_TYPES_NOT_ALLOWED);
        }
        if (!formatProblematicDomains.isEmpty()) {
            addValidationMessage(EngineMessage.ACTION_TYPE_FAILED_DECREASING_COMPATIBILITY_VERSION_CAUSES_STORAGE_FORMAT_DOWNGRADING);
            getReturnValue().getValidationMessages().addAll(ReplacementUtils.replaceWith("formatDowngradedDomains", formatProblematicDomains, "," , formatProblematicDomains.size()));
        }
        if (!typeProblematicDomains.isEmpty()) {
            addValidationMessage(EngineMessage.ACTION_TYPE_FAILED_STORAGE_DOMAINS_ARE_NOT_SUPPORTED_IN_DOWNGRADED_VERSION);
            getReturnValue().getValidationMessages().addAll(ReplacementUtils.replaceWith("unsupportedVersionDomains", typeProblematicDomains , ",", typeProblematicDomains.size()));
        }

        return typeProblematicDomains.isEmpty() && formatProblematicDomains.isEmpty() && !failOnSupportedTypeMixing;
    }

    protected StorageDomainToPoolRelationValidator getAttachDomainValidator(StorageDomainStatic domainStatic) {
        return new StorageDomainToPoolRelationValidator(domainStatic, getStoragePool());
    }

    protected boolean checkAllClustersLevel() {
        boolean returnValue = true;
        List<Cluster> clusters = getClusterDao().getAllForStoragePool(getStoragePool().getId());
        List<String> lowLevelClusters = new ArrayList<>();
        for (Cluster cluster : clusters) {
            if (getStoragePool().getCompatibilityVersion().compareTo(cluster.getCompatibilityVersion()) > 0) {
                lowLevelClusters.add(cluster.getName());
            }
        }
        if (!lowLevelClusters.isEmpty()) {
            returnValue = false;
            getReturnValue().getValidationMessages().add(String.format("$ClustersList %1$s",
                    StringUtils.join(lowLevelClusters, ",")));
            getReturnValue()
                    .getValidationMessages()
                    .add(EngineMessage.ERROR_CANNOT_UPDATE_STORAGE_POOL_COMPATIBILITY_VERSION_BIGGER_THAN_CLUSTERS
                            .toString());
        }
        return returnValue;
    }

    private StorageDomain getMasterDomain() {
        if (masterDomainForPool == null) {
            Guid masterId = getStorageDomainDao().getMasterStorageDomainIdForPool(getStoragePoolId());
            if (Guid.Empty.equals(masterId)) {
                masterDomainForPool = getStorageDomainDao().get(masterId);
            }
        }
        return masterDomainForPool;
    }

    @Override
    protected NetworkDao getNetworkDao() {
        return getDbFacade().getNetworkDao();
    }

    protected NetworkValidator getNetworkValidator(Network network) {
        return new NetworkValidator(vmDao, network);
    }

    protected StoragePoolValidator createStoragePoolValidator() {
        return new StoragePoolValidator(getStoragePool());
    }

    protected boolean isStoragePoolVersionSupported() {
        return VersionSupport.checkVersionSupported(getStoragePool().getCompatibilityVersion());
    }

    /**
     * Copy properties from old entity which assumed not to be available in the param object.
     */
    private static void copyUnchangedStoragePoolProperties(StoragePool newStoragePool, StoragePool oldStoragePool) {
        newStoragePool.setStoragePoolFormatType(oldStoragePool.getStoragePoolFormatType());
    }

    @Override
    public String getEntityType() {
        return VdcObjectType.StoragePool.getVdcObjectTranslation();
    }

    @Override
    public String getEntityOldName() {
        return getOldStoragePool().getName();
    }

    @Override
    public String getEntityNewName() {
        return getParameters().getStoragePool().getName();
    }

    @Override
    public void setEntityId(AuditLogableBase logable) {
        logable.setStoragePoolId(getOldStoragePool().getId());
    }

    private Guid getOldMacPoolId() {
        return getOldStoragePool().getMacPoolId();
    }

    private Guid getNewMacPoolId() {
        return getParameters().getStoragePool() == null ? null : getParameters().getStoragePool().getMacPoolId();
    }

    private StoragePool getOldStoragePool() {
        if (oldStoragePool == null) {
            oldStoragePool = getStoragePoolDao().get(getStoragePool().getId());
        }

        return oldStoragePool;
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        final List<PermissionSubject> result = new ArrayList<>(super.getPermissionCheckSubjects());

        final Guid macPoolId = getNewMacPoolId();
        final boolean changingPoolDefinition = macPoolId != null && !macPoolId.equals(getOldMacPoolId());
        if (changingPoolDefinition) {
            result.add(new PermissionSubject(macPoolId, VdcObjectType.MacPool, ActionGroup.CONFIGURE_MAC_POOL));
        }

        return result;
    }

    ManagementNetworkUtil getManagementNetworkUtil() {
        return managementNetworkUtil;
    }
}
