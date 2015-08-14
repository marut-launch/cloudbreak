package com.sequenceiq.cloudbreak.converter.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.springframework.stereotype.Component;

import com.sequenceiq.cloudbreak.cloud.model.CloudStack;
import com.sequenceiq.cloudbreak.cloud.model.Group;
import com.sequenceiq.cloudbreak.cloud.model.Image;
import com.sequenceiq.cloudbreak.cloud.model.InstanceTemplate;
import com.sequenceiq.cloudbreak.cloud.model.Network;
import com.sequenceiq.cloudbreak.cloud.model.Security;
import com.sequenceiq.cloudbreak.cloud.model.SecurityRule;
import com.sequenceiq.cloudbreak.cloud.model.Subnet;
import com.sequenceiq.cloudbreak.cloud.model.Volume;
import com.sequenceiq.cloudbreak.domain.InstanceGroup;
import com.sequenceiq.cloudbreak.domain.InstanceGroupType;
import com.sequenceiq.cloudbreak.domain.InstanceMetaData;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.domain.Template;
import com.sequenceiq.cloudbreak.repository.SecurityRuleRepository;
import com.sequenceiq.cloudbreak.service.stack.connector.VolumeUtils;
import com.sequenceiq.cloudbreak.service.stack.flow.ReflectionUtils;

@Component
public class StackToCloudStackConverter {

    @Inject
    private SecurityRuleRepository securityRuleRepository;

    public CloudStack convert(Stack stack) {
        return convert(stack, null, null);
    }

    public CloudStack convert(Stack stack, String coreUserData, String gateWayUserData) {
        List<Group> instanceGroups = buildInstanceGroups(stack.getInstanceGroupsAsList());
        Image image = buildImage(stack, coreUserData, gateWayUserData);
        Network network = buildNetwork(stack);
        Security security = buildSecurity(stack);
        return new CloudStack(instanceGroups, network, security, image);
    }

    public List<Group> buildInstanceGroups(List<InstanceGroup> instanceGroups) {
        // sort by name to avoid shuffling the different instance groups
        Collections.sort(instanceGroups);
        List<Group> groups = new ArrayList<>();
        long privateId = getFirstValidPrivateId(instanceGroups);
        for (InstanceGroup instanceGroup : instanceGroups) {
            Group group = new Group(instanceGroup.getGroupName(), instanceGroup.getInstanceGroupType());
            Template template = instanceGroup.getTemplate();
            int desiredNodeCount = instanceGroup.getNodeCount();
            // existing instances
            for (InstanceMetaData instanceMetaData : instanceGroup.getInstanceMetaData()) {
                group.addInstance(buildInstanceTemplate(template, instanceGroup.getGroupName(), instanceMetaData.getPrivateId()));
            }
            // new instances
            int existingNodesSize = group.getInstances().size();
            if (existingNodesSize < desiredNodeCount) {
                for (long i = 0; i < desiredNodeCount - existingNodesSize; i++) {
                    group.addInstance(buildInstanceTemplate(template, instanceGroup.getGroupName(), privateId++));
                }
            }
            groups.add(group);
        }
        return groups;
    }

    public List<InstanceTemplate> buildInstanceTemplates(Stack stack) {
        List<Group> groups = buildInstanceGroups(stack.getInstanceGroupsAsList());
        List<InstanceTemplate> instanceTemplates = new ArrayList<>();
        for (Group group : groups) {
            instanceTemplates.addAll(group.getInstances());
        }
        return instanceTemplates;
    }

    public InstanceTemplate buildInstanceTemplate(Template template, String name, long privateId) {
        InstanceTemplate instance = new InstanceTemplate(template.getInstanceTypeName(), name, privateId);
        for (int i = 0; i < template.getVolumeCount(); i++) {
            Volume volume = new Volume(VolumeUtils.VOLUME_PREFIX + (i + 1), template.getVolumeTypeName(), template.getVolumeSize());
            instance.addVolume(volume);
        }
        return instance;
    }

    private Image buildImage(Stack stack, String coreUserData, String gateWayUserData) {
        Image image = new Image(stack.getImage());
        image.putUserData(InstanceGroupType.CORE, coreUserData);
        image.putUserData(InstanceGroupType.GATEWAY, gateWayUserData);
        return image;
    }

    private Network buildNetwork(Stack stack) {
        com.sequenceiq.cloudbreak.domain.Network stackNetwork = stack.getNetwork();
        Subnet subnet = new Subnet(stackNetwork.getSubnetCIDR());
        Network network = new Network(subnet);
        network.putAll(ReflectionUtils.getDeclaredFields(stackNetwork));
        return network;
    }

    private Security buildSecurity(Stack stack) {
        Long id = stack.getSecurityGroup().getId();
        List<com.sequenceiq.cloudbreak.domain.SecurityRule> securityRules = securityRuleRepository.findAllBySecurityGroupId(id);
        List<SecurityRule> rules = new ArrayList<>();
        for (com.sequenceiq.cloudbreak.domain.SecurityRule securityRule : securityRules) {
            rules.add(new SecurityRule(securityRule.getCidr(), securityRule.getPorts(), securityRule.getProtocol()));
        }
        return new Security(rules);
    }

    private Long getFirstValidPrivateId(List<InstanceGroup> instanceGroups) {
        long highest = 0;
        for (InstanceGroup instanceGroup : instanceGroups) {
            for (InstanceMetaData metaData : instanceGroup.getInstanceMetaData()) {
                Long privateId = metaData.getPrivateId();
                if (privateId == null) {
                    continue;
                }
                if (privateId > highest) {
                    highest = privateId;
                }
            }
        }
        return highest == 0 ? 0 : highest + 1;
    }

}