package org.netbeans.gradle.project.license;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jtrim.cancel.Cancellation;
import org.jtrim.cancel.CancellationToken;
import org.jtrim.concurrent.CancelableTask;
import org.jtrim.concurrent.MonitorableTaskExecutor;
import org.jtrim.concurrent.TaskExecutor;
import org.jtrim.concurrent.TaskExecutors;
import org.jtrim.property.PropertySource;
import org.jtrim.utils.ExceptionHelper;
import org.netbeans.gradle.project.properties.NbProperties;
import org.netbeans.gradle.project.util.CloseableAction;
import org.netbeans.gradle.project.util.NbBiFunction;

public final class LicenseManager<T> {
    private static final Logger LOGGER = Logger.getLogger(LicenseManager.class.getName());

    private final Impl<T, ?, ?> impl;

    public <LK, LD extends LicenseDef> LicenseManager(
            TaskExecutor executor,
            LicenseStore<LD> licenseStore,
            NbBiFunction<? super T, ? super LicenseHeaderInfo, ? extends LK> licenseKeyFactory,
            NbBiFunction<? super T, ? super LK, ? extends LD> licenseDefFactory) {
        this.impl = new Impl<>(executor, licenseStore, licenseKeyFactory, licenseDefFactory);
    }

    public String tryGetRegisteredLicenseName(T ownerModel, LicenseHeaderInfo headerInfo) {
        return impl.tryGetRegisteredLicenseName(ownerModel, headerInfo);
    }

    public PropertySource<CloseableAction> getRegisterListenerAction(
            PropertySource<? extends T> modelProperty,
            PropertySource<? extends LicenseHeaderInfo> headerProperty) {
        ExceptionHelper.checkNotNullArgument(modelProperty, "modelProperty");
        ExceptionHelper.checkNotNullArgument(headerProperty, "headerProperty");

        return NbProperties.combine(headerProperty, modelProperty, new NbBiFunction<LicenseHeaderInfo, T, CloseableAction>() {
            @Override
            public CloseableAction apply(LicenseHeaderInfo headerInfo, T model) {
                return getRegisterListenerAction(model, headerInfo);
            }
        });
    }

    private CloseableAction getRegisterListenerAction(
            final T ownerModel,
            final LicenseHeaderInfo header) {
        ExceptionHelper.checkNotNullArgument(ownerModel, "ownerModel");

        return new CloseableAction() {
            @Override
            public CloseableAction.Ref open() {
                return impl.registerLicense(ownerModel, header);
            }
        };
    }

    private static final class Impl<T, LK, LD extends LicenseDef> {
        private final MonitorableTaskExecutor syncExecutor;

        private final LicenseStore<LD> licenseStore;
        private final NbBiFunction<? super T, ? super LicenseHeaderInfo, ? extends LK> licenseKeyFactory;
        private final NbBiFunction<? super T, ? super LK, ? extends LD> licenseDefFactory;

        private final Map<LK, RegisteredLicense<LD>> licenseRegistartions;

        public Impl(
                TaskExecutor executor,
                LicenseStore<LD> licenseStore,
                NbBiFunction<? super T, ? super LicenseHeaderInfo, ? extends LK> licenseKeyFactory,
                NbBiFunction<? super T, ? super LK, ? extends LD> licenseDefFactory) {
            ExceptionHelper.checkNotNullArgument(licenseStore, "licenseStore");
            ExceptionHelper.checkNotNullArgument(licenseKeyFactory, "licenseKeyFactory");
            ExceptionHelper.checkNotNullArgument(licenseDefFactory, "licenseDefFactory");

            this.syncExecutor = TaskExecutors.inOrderExecutor(executor);
            this.licenseStore = licenseStore;
            this.licenseKeyFactory = licenseKeyFactory;
            this.licenseDefFactory = licenseDefFactory;
            this.licenseRegistartions = new HashMap<>();
        }

        public String tryGetRegisteredLicenseName(T ownerModel, LicenseHeaderInfo headerInfo) {
            ExceptionHelper.checkNotNullArgument(ownerModel, "ownerModel");
            ExceptionHelper.checkNotNullArgument(headerInfo, "headerInfo");

            LK key = tryGetLicenseKey(ownerModel, headerInfo);
            RegisteredLicense<LD> registration = key != null
                    ? licenseRegistartions.get(key)
                    : null;

            String licenseId = registration != null
                    ? registration.getLicenseId()
                    : headerInfo.getLicenseName();

            return licenseStore.containsLicense(licenseId) ? licenseId : null;
        }

        private void removeLicense(RegisteredLicense<LD> registration) throws IOException {
            assert syncExecutor.isExecutingInThis();

            licenseStore.removeLicense(registration.getLicenseId());
        }

        private void addLicense(RegisteredLicense<LD> registration) throws IOException {
            assert syncExecutor.isExecutingInThis();

            licenseStore.addLicense(registration.licenseDef);
        }

        private void doUnregister(final LK key) {
            syncExecutor.execute(Cancellation.UNCANCELABLE_TOKEN, new CancelableTask() {
                @Override
                public void execute(CancellationToken cancelToken) throws IOException {
                    RegisteredLicense<LD> registration = licenseRegistartions.get(key);
                    if (registration == null) {
                        LOGGER.log(Level.WARNING, "Too many unregister call to LicenseManager.", new Exception());
                        return;
                    }

                    if (registration.release()) {
                        licenseRegistartions.remove(key);
                        removeLicense(registration);
                    }
                }
            }, null);
        }

        private void doRegister(final T ownerModel, final LK key) {
            syncExecutor.execute(Cancellation.UNCANCELABLE_TOKEN, new CancelableTask() {
                @Override
                public void execute(CancellationToken cancelToken) throws IOException {
                    RegisteredLicense<LD> registration = licenseRegistartions.get(key);
                    if (registration == null) {
                        registration = new RegisteredLicense<>(getLicenseDef(ownerModel, key));
                        licenseRegistartions.put(key, registration);
                        addLicense(registration);
                    }
                    else {
                        registration.use();
                    }
                }
            }, null);
        }

        public CloseableAction.Ref registerLicense(T ownerModel, LicenseHeaderInfo header) {
            ExceptionHelper.checkNotNullArgument(ownerModel, "ownerModel");

            if (header == null) {
                return CloseableAction.CLOSED_REF;
            }

            final LK key = tryGetLicenseKey(ownerModel, header);
            if (key == null) {
                return CloseableAction.CLOSED_REF;
            }

            doRegister(ownerModel, key);

            return new CloseableAction.Ref() {
                private final AtomicBoolean unregistered = new AtomicBoolean(false);

                @Override
                public void close() {
                    if (unregistered.compareAndSet(false, true)) {
                        doUnregister(key);
                    }
                }
            };
        }

        private LK tryGetLicenseKey(T ownerModel, LicenseHeaderInfo headerInfo) {
            return licenseKeyFactory.apply(ownerModel, headerInfo);
        }

        private LD getLicenseDef(T ownerModel, LK key) {
            return licenseDefFactory.apply(ownerModel, key);
        }
    }

    private static final class RegisteredLicense<LD extends LicenseDef> {
        private final LD licenseDef;
        private int useCount;

        public RegisteredLicense(LD licenseDef) {
            this.useCount = 1;
            this.licenseDef = licenseDef;
        }

        public String getLicenseId() {
            return licenseDef.getLicenseId();
        }

        public void use() {
            useCount++;
        }

        public boolean release() {
            useCount--;
            return useCount <= 0;
        }
    }
}
