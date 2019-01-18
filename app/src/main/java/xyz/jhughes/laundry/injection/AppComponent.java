package xyz.jhughes.laundry.injection;

import javax.inject.Singleton;

import dagger.Component;
import xyz.jhughes.laundry.AnalyticsApplication;

@Singleton
@Component(modules = AppModule.class)
public interface AppComponent {
    void inject(AnalyticsApplication analyticsApplication);
}
