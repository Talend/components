package org.talend.components.salesforce;

import javax.inject.Inject;

import org.talend.components.api.Constants;
import org.talend.components.api.properties.ComponentProperties;
import org.talend.components.api.service.ComponentService;
import org.talend.components.api.wizard.AbstractComponentWizardDefintion;
import org.talend.components.api.wizard.ComponentWizard;
import org.talend.components.api.wizard.ComponentWizardDefinition;
import org.talend.components.api.wizard.WizardImageType;

import aQute.bnd.annotation.component.Reference;

@org.springframework.stereotype.Component(Constants.COMPONENT_WIZARD_BEAN_PREFIX
        + SalesforceConnectionWizardDefinition.COMPONENT_WIZARD_NAME)
@aQute.bnd.annotation.component.Component(name = Constants.COMPONENT_WIZARD_BEAN_PREFIX
        + SalesforceConnectionWizardDefinition.COMPONENT_WIZARD_NAME, provide = ComponentWizardDefinition.class)
public class SalesforceConnectionWizardDefinition extends AbstractComponentWizardDefintion {

    public static final String COMPONENT_WIZARD_NAME = "salesforce"; //$NON-NLS-1$

    @Inject
    ComponentService compService;

    @Reference
    public void setupComponentServiceFromOsgi(ComponentService compService) {
        this.compService = compService;
    }

    @Override
    public String getName() {
        return COMPONENT_WIZARD_NAME;
    }

    @Override
    public ComponentWizard createWizard(String location) {
        return new SalesforceConnectionWizard(this, location, compService);
    }

    @Override
    public boolean supportsProperties(ComponentProperties properties) {
        if (properties instanceof SalesforceConnectionProperties) {
            return true;
        }
        return false;
    }

    @Override
    public ComponentWizard createWizard(ComponentProperties properties, String location) {
        SalesforceConnectionWizard wizard = (SalesforceConnectionWizard) createWizard(location);
        wizard.setupProperties((SalesforceConnectionProperties) properties);
        return wizard;
    }

    @Override
    public String getPngImagePath(WizardImageType imageType) {
        switch (imageType) {
        case TREE_ICON_16X16:
            return "connectionWizardIcon.png"; //$NON-NLS-1$
        case WIZARD_BANNER_75X66:
            return "salesforceWizardBanner.png"; //$NON-NLS-1$

        default:
            // will return null
        }
        return null;
    }

    @Override
    public boolean isTopLevel() {
        return true;
    }

}