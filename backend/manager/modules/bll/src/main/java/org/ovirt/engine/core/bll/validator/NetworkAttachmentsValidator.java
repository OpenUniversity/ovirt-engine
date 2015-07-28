package org.ovirt.engine.core.bll.validator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.collections.Bag;
import org.apache.commons.collections.bag.HashBag;
import org.ovirt.engine.core.bll.ValidationResult;
import org.ovirt.engine.core.common.businessentities.BusinessEntityMap;
import org.ovirt.engine.core.common.businessentities.network.Network;
import org.ovirt.engine.core.common.businessentities.network.NetworkAttachment;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.utils.NetworkUtils;
import org.ovirt.engine.core.utils.ReplacementUtils;
import org.ovirt.engine.core.utils.collections.MultiValueMapUtils;

/**
 * The {@code NetworkAttachmentsValidator} performs validation on the entire network attachments as a whole and for
 * cross network attachments configuration. For a specific network attachment entity validation use
 * {@link org.ovirt.engine.core.bll.validator.NetworkAttachmentValidator};
 */
public class NetworkAttachmentsValidator {

    public static final String VAR_NETWORK_INTERFACES_NOT_EXCLUSIVELY_USED_BY_NETWORK_LIST = "NETWORK_INTERFACES_NOT_EXCLUSIVELY_USED_BY_NETWORK_LIST";
    private final Collection<NetworkAttachment> attachmentsToConfigure;
    private final BusinessEntityMap<Network> networkBusinessEntityMap;

    public NetworkAttachmentsValidator(Collection<NetworkAttachment> attachmentsToConfigure,
            BusinessEntityMap<Network> networkBusinessEntityMap) {
        this.attachmentsToConfigure = attachmentsToConfigure;
        this.networkBusinessEntityMap = networkBusinessEntityMap;
    }

    public ValidationResult validateNetworkExclusiveOnNics() {
        Map<String, List<NetworkType>> nicNameToNetworkTypesMap = createNicNameToNetworkTypesMap();
        List<String> violatedNics = findViolatedNics(nicNameToNetworkTypesMap);

        if (violatedNics.isEmpty()) {
            return ValidationResult.VALID;
        } else {
            return new ValidationResult(EngineMessage.NETWORK_INTERFACES_NOT_EXCLUSIVELY_USED_BY_NETWORK,
                ReplacementUtils.replaceWith(VAR_NETWORK_INTERFACES_NOT_EXCLUSIVELY_USED_BY_NETWORK_LIST,
                    violatedNics));
        }
    }

    private List<String> findViolatedNics(Map<String, List<NetworkType>> nicNameToNetworkTypesMap) {
        List<String> violatedNics = new ArrayList<>();
        for (Entry<String, List<NetworkType>> nicNameToNetworkTypes : nicNameToNetworkTypesMap.entrySet()) {
            String nicName = nicNameToNetworkTypes.getKey();
            List<NetworkType> networkTypes = nicNameToNetworkTypes.getValue();
            if (!validateNetworkTypesOnNic(networkTypes)) {
                violatedNics.add(nicName);
            }
        }
        return violatedNics;
    }

    private Map<String, List<NetworkType>> createNicNameToNetworkTypesMap() {
        Map<String, List<NetworkType>> nicNameToNetworkTypes = new HashMap<>();
        for (NetworkAttachment attachment : attachmentsToConfigure) {
            String nicName = attachment.getNicName();
            // have to check since if null, multiple results would be merged producing invalid results.
            if (nicName == null) {
                throw new IllegalArgumentException("nic name cannot be null");
            }

            Network networkToConfigure = networkBusinessEntityMap.get(attachment.getNetworkId());
            NetworkType networkTypeToAdd = determineNetworkType(networkToConfigure);

            MultiValueMapUtils.ListCreator<NetworkType> listCreator = new MultiValueMapUtils.ListCreator<>();
            MultiValueMapUtils.addToMap(nicName, networkTypeToAdd, nicNameToNetworkTypes, listCreator);
        }
        return nicNameToNetworkTypes;
    }

    NetworkType determineNetworkType(Network network) {
        return NetworkUtils.isVlan(network)
                ? NetworkType.VLAN
                : network.isVmNetwork() ? NetworkType.VM : NetworkType.NON_VM;
    }

    /**
     * Make sure that if the given interface has a VM network on it then there is nothing else on the interface, or if
     * the given interface is a VLAN network, than there is no VM network on the interface.<br>
     * Other combinations are either legal or illegal but are not a concern of this method.
     *
     * @return true if for given nic there's either nothing, sole VM network,
     * or at most one NON-VM network with any number of VLANs.
     */
    private boolean validateNetworkTypesOnNic(List<NetworkType> networksOnInterface) {
        if (networksOnInterface.size() <= 1) {
            return true;
        }

        Bag networkTypes = new HashBag(networksOnInterface);
        boolean vmNetworkIsNotSoleNetworkAssigned = networkTypes.contains(NetworkType.VM);
        boolean moreThanOneNonVmNetworkAssigned =
            networkTypes.contains(NetworkType.NON_VM) && networkTypes.getCount(NetworkType.NON_VM) > 1;

        return !(vmNetworkIsNotSoleNetworkAssigned || moreThanOneNonVmNetworkAssigned);
    }

    public ValidationResult verifyUserAttachmentsDoesNotReferenceSameNetworkDuplicately() {
        Map<String, List<Guid>> networkNameToIdsOfReferencingAttachments = new HashMap<>();
        MultiValueMapUtils.ListCreator<Guid> creator = new MultiValueMapUtils.ListCreator<>();

        for (NetworkAttachment networkAttachment : attachmentsToConfigure) {
            Network network = networkBusinessEntityMap.get(networkAttachment.getNetworkId());

            MultiValueMapUtils.addToMap(network.getName(),
                networkAttachment.getId(),
                networkNameToIdsOfReferencingAttachments,
                creator);
        }


        for (Entry<String, List<Guid>> entry : networkNameToIdsOfReferencingAttachments.entrySet()) {
            List<Guid> referencingAttachments = entry.getValue();
            String networkName = entry.getKey();
            if (referencingAttachments.size() > 1) {
                List<String> replacements = new ArrayList<>();
                replacements.addAll(ReplacementUtils.replaceWith(
                    "ACTION_TYPE_FAILED_NETWORK_ATTACHMENTS_REFERENCES_SAME_NETWORK_DUPLICATELY_LIST",
                    referencingAttachments));
                replacements.add(ReplacementUtils.createSetVariableString(
                    "ACTION_TYPE_FAILED_NETWORK_ATTACHMENTS_REFERENCES_SAME_NETWORK_DUPLICATELY_ENTITY", networkName));

                return new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_NETWORK_ATTACHMENTS_REFERENCES_SAME_NETWORK_DUPLICATELY,
                    replacements);
            }
        }

        return ValidationResult.VALID;
    }

    enum NetworkType {
        VM,
        NON_VM,
        VLAN
    }
}
