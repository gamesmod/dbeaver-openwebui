package dbeaver.openwebui.p2test;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.IProvisioningAgentProvider;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.engine.IProvisioningPlan;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.operations.InstallOperation;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs the same InstallOperation that Help → Install New Software uses, for the IUs of the category
 * of an update site, inside a real DBeaver installation. Prints the plan and commits it.
 *   -application dbeaver.openwebui.p2test.app <repository URI> <iu id>[,<iu id>...]
 * Exit code 0 when the operation resolved and was performed.
 */
public class InstallApp implements IApplication {

    @Override
    public Object start(IApplicationContext context) throws Exception {
        String[] args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        List<String> plain = new ArrayList<>();
        for (String a : args) {
            if (!a.startsWith("-")) {
                plain.add(a);
            }
        }
        URI repo = URI.create(plain.get(0));
        String[] ids = plain.get(1).split(",");

        Bundle bundle = FrameworkUtil.getBundle(getClass());
        BundleContext ctx = bundle.getBundleContext();
        ServiceReference<IProvisioningAgentProvider> ref = ctx.getServiceReference(IProvisioningAgentProvider.class);
        IProvisioningAgent agent = ctx.getService(ref).createAgent(null); // running installation
        IProfileRegistry registry = agent.getService(IProfileRegistry.class);
        IProfile profile = registry.getProfile(IProfileRegistry.SELF);
        System.out.println("PROFILE " + profile.getProfileId());
        printInstalled("BEFORE", profile);

        IMetadataRepositoryManager mrm = agent.getService(IMetadataRepositoryManager.class);
        IMetadataRepository mr = mrm.loadRepository(repo, new NullProgressMonitor());
        List<IInstallableUnit> toInstall = new ArrayList<>();
        for (String id : ids) {
            IInstallableUnit iu = mr.query(QueryUtil.createLatestQuery(QueryUtil.createIUQuery(id.trim())), null)
                .iterator().next();
            System.out.println("SELECT " + iu.getId() + " " + iu.getVersion());
            toInstall.add(iu);
        }

        ProvisioningSession session = new ProvisioningSession(agent);
        InstallOperation op = new InstallOperation(session, toInstall);
        op.setProfileId(profile.getProfileId());
        op.getProvisioningContext().setMetadataRepositories(repo);
        op.getProvisioningContext().setArtifactRepositories(repo);
        IStatus status = op.resolveModal(new NullProgressMonitor());
        System.out.println("RESOLVE " + status);
        for (IStatus child : status.getChildren()) {
            System.out.println("  " + child.getMessage());
        }
        if (status.getSeverity() == IStatus.ERROR) {
            return 2;
        }
        IProvisioningPlan plan = op.getProvisioningPlan();
        for (IInstallableUnit iu : plan.getAdditions().query(QueryUtil.createIUAnyQuery(), null)) {
            if (iu.getId().contains("openwebui")) System.out.println("PLAN + " + iu.getId() + " " + iu.getVersion());
        }
        for (IInstallableUnit iu : plan.getRemovals().query(QueryUtil.createIUAnyQuery(), null)) {
            if (iu.getId().contains("openwebui")) System.out.println("PLAN - " + iu.getId() + " " + iu.getVersion());
        }
        IStatus result = op.getProvisioningJob(null).runModal(new NullProgressMonitor());
        System.out.println("PERFORM " + result);
        printInstalled("AFTER", registry.getProfile(IProfileRegistry.SELF));
        return result.isOK() ? IApplication.EXIT_OK : 3;
    }

    private static void printInstalled(String label, IProfile profile) {
        Set<String> lines = new TreeSet<>();
        for (IInstallableUnit iu : profile.query(QueryUtil.createIUAnyQuery(), null)) {
            if (iu.getId().contains("openwebui")) {
                lines.add(iu.getId() + " " + iu.getVersion() + (Boolean.parseBoolean(profile.getInstallableUnitProperty(iu, "org.eclipse.equinox.p2.type.root")) ? " [root]" : ""));
            }
        }
        System.out.println(label + ":");
        lines.forEach(l -> System.out.println("  " + l));
    }

    @Override
    public void stop() {
    }
}
