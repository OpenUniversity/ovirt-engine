package org.ovirt.engine.ui.userportal.section.main.presenter;

import org.ovirt.engine.ui.common.widget.AlertManager;
import org.ovirt.engine.ui.userportal.ApplicationDynamicMessages;
import org.ovirt.engine.ui.userportal.place.UserPortalPlaceManager;
import com.google.gwt.event.shared.EventBus;
import com.google.gwt.event.shared.GwtEvent.Type;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.Presenter;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.annotations.ContentSlot;
import com.gwtplatform.mvp.client.annotations.ProxyCodeSplit;
import com.gwtplatform.mvp.client.proxy.Proxy;
import com.gwtplatform.mvp.client.proxy.RevealContentHandler;

public class MainSectionPresenter extends Presenter<MainSectionPresenter.ViewDef, MainSectionPresenter.ProxyDef> {

    @ProxyCodeSplit
    public interface ProxyDef extends Proxy<MainSectionPresenter> {
    }

    public interface ViewDef extends View {
    }

    @ContentSlot
    public static final Type<RevealContentHandler<?>> TYPE_SetHeader = new Type<>();

    @ContentSlot
    public static final Type<RevealContentHandler<?>> TYPE_SetMainContent = new Type<>();

    private final HeaderPresenterWidget header;
    private final UserPortalPlaceManager placeManager;
    private final String basicGuideUrl;
    private final String extendedGuideUrl;
    private final AlertManager alertManager;

    @Inject
    public MainSectionPresenter(EventBus eventBus, ViewDef view, ProxyDef proxy, HeaderPresenterWidget header,
            UserPortalPlaceManager userPortalPlaceManager, ApplicationDynamicMessages dynamicMessages,
            AlertManager alertManager) {
        super(eventBus, view, proxy, RevealType.RootLayout);
        this.header = header;
        this.placeManager = userPortalPlaceManager;
        this.basicGuideUrl = dynamicMessages.guideUrl();
        this.extendedGuideUrl = dynamicMessages.extendedGuideUrl();
        this.alertManager = alertManager;
    }

    @Override
    protected void onReveal() {
        super.onReveal();

        setInSlot(TYPE_SetHeader, header);

        // Enable alerts within the scope of main section
        alertManager.setCanShowAlerts(true);
    }

    @Override
    protected void onHide() {
        super.onHide();

        // Disable alerts outside the scope of main section
        alertManager.setCanShowAlerts(false);
    }

    @Override
    protected void onReset() {
        // This is called before the place is actually changed, so we need to reverse the logic, and set the
        // extended guide when switching away from the basic (but it is still visible), and vice versa.
        if (placeManager.isMainSectionBasicPlaceVisible()) {
            header.setGuideUrl(basicGuideUrl);
        } else {
            header.setGuideUrl(extendedGuideUrl);
        }
    }
}
