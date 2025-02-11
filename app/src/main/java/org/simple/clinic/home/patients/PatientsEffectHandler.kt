package org.simple.clinic.home.patients

import android.annotation.SuppressLint
import com.f2prateek.rx.preferences2.Preference
import com.spotify.mobius.functions.Consumer
import com.spotify.mobius.rx2.RxMobius
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import org.simple.clinic.appupdate.AppUpdateNotificationScheduler
import org.simple.clinic.appupdate.AppUpdateState
import org.simple.clinic.appupdate.CheckAppUpdateAvailability
import org.simple.clinic.drugstockreminders.DrugStockReminder
import org.simple.clinic.facility.Facility
import org.simple.clinic.main.TypedPreference
import org.simple.clinic.main.TypedPreference.Type.DrugStockReportLastCheckedAt
import org.simple.clinic.main.TypedPreference.Type.IsDrugStockReportFilled
import org.simple.clinic.user.UserSession
import org.simple.clinic.user.refreshuser.RefreshCurrentUser
import org.simple.clinic.util.UserClock
import org.simple.clinic.util.UtcClock
import org.simple.clinic.util.filterAndUnwrapJust
import org.simple.clinic.util.scheduler.SchedulersProvider
import org.simple.clinic.util.toLocalDateAtZone
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import javax.inject.Named

class PatientsEffectHandler @AssistedInject constructor(
    private val schedulers: SchedulersProvider,
    private val refreshCurrentUser: RefreshCurrentUser,
    private val userSession: UserSession,
    private val utcClock: UtcClock,
    private val userClock: UserClock,
    private val checkAppUpdate: CheckAppUpdateAvailability,
    private val appUpdateNotificationScheduler: AppUpdateNotificationScheduler,
    @Named("approved_status_dismissed") private val hasUserDismissedApprovedStatusPref: Preference<Boolean>,
    @Named("app_update_last_shown_at") private val appUpdateDialogShownAtPref: Preference<Instant>,
    @Named("approval_status_changed_at") private val approvalStatusUpdatedAtPref: Preference<Instant>,
    private val drugStockReminder: DrugStockReminder,
    @TypedPreference(DrugStockReportLastCheckedAt) private val drugStockReportLastCheckedAt: Preference<Instant>,
    @TypedPreference(IsDrugStockReportFilled) private val isDrugStockReportFilled: Preference<Optional<Boolean>>,
    private val currentFacility: Observable<Facility>,
    @Assisted private val viewEffectsConsumer: Consumer<PatientsTabViewEffect>
) {

  @AssistedFactory
  interface Factory {
    fun create(
        viewEffectsConsumer: Consumer<PatientsTabViewEffect>
    ): PatientsEffectHandler
  }

  fun build(): ObservableTransformer<PatientsTabEffect, PatientsTabEvent> {
    return RxMobius
        .subtypeEffectHandler<PatientsTabEffect, PatientsTabEvent>()
        .addTransformer(RefreshUserDetails::class.java, refreshCurrentUser())
        .addTransformer(LoadUser::class.java, loadUser())
        .addTransformer(LoadInfoForShowingApprovalStatus::class.java, loadRequiredInfoForShowingApprovalStatus())
        .addConsumer(SetDismissedApprovalStatus::class.java, { hasUserDismissedApprovedStatusPref.set(it.dismissedStatus) }, schedulers.io())
        .addTransformer(LoadInfoForShowingAppUpdateMessage::class.java, loadInfoForShowingAppUpdate())
        .addConsumer(TouchAppUpdateShownAtTime::class.java, { appUpdateDialogShownAtPref.set(Instant.now(utcClock)) }, schedulers.io())
        .addConsumer(ScheduleAppUpdateNotification::class.java, { appUpdateNotificationScheduler.schedule() }, schedulers.io())
        .addConsumer(PatientsTabViewEffect::class.java, viewEffectsConsumer::accept, schedulers.ui())
        .addTransformer(LoadDrugStockReportStatus::class.java, loadDrugStockReportStatus())
        .addTransformer(LoadInfoForShowingDrugStockReminder::class.java, loadInfoForShowingDrugStockReminder())
        .addConsumer(TouchDrugStockReportLastCheckedAt::class.java, { drugStockReportLastCheckedAt.set(Instant.now(utcClock)) }, schedulers.io())
        .addConsumer(TouchIsDrugStockReportFilled::class.java, {
          isDrugStockReportFilled.set(Optional.of(it.isDrugStockReportFilled))
        }, schedulers.io())
        .addTransformer(LoadCurrentFacility::class.java, loadCurrentFacility())
        .build()
  }

  private fun loadCurrentFacility(): ObservableTransformer<LoadCurrentFacility, PatientsTabEvent> {
    return ObservableTransformer { effects ->
      effects
          .observeOn(schedulers.io())
          .switchMap { currentFacility }
          .map(::CurrentFacilityLoaded)
    }
  }

  private fun loadInfoForShowingDrugStockReminder(): ObservableTransformer<LoadInfoForShowingDrugStockReminder, PatientsTabEvent> {
    return ObservableTransformer { effects ->
      effects
          .observeOn(schedulers.io())
          .map {
            val drugStockReportLastCheckedAt = drugStockReportLastCheckedAt.get().toLocalDateAtZone(userClock.zone)
            val isDrugStockReportFilled = isDrugStockReportFilled.get()

            RequiredInfoForShowingDrugStockReminderLoaded(
                currentDate = LocalDate.now(userClock),
                drugStockReportLastCheckedAt = drugStockReportLastCheckedAt,
                isDrugStockReportFilled = isDrugStockReportFilled
            )
          }
    }
  }

  private fun loadDrugStockReportStatus(): ObservableTransformer<LoadDrugStockReportStatus, PatientsTabEvent> {
    return ObservableTransformer { effects ->
      effects
          .observeOn(schedulers.io())
          .map { drugStockReminder.reminderForDrugStock(it.date) }
          .map(::DrugStockReportLoaded)
    }
  }

  private fun refreshCurrentUser(): ObservableTransformer<RefreshUserDetails, PatientsTabEvent> {
    return ObservableTransformer { effects ->
      effects
          .map { createRefreshUserCompletable() }
          .doOnNext(::runRefreshUserTask)
          .flatMap { Observable.empty<PatientsTabEvent>() }
    }
  }

  private fun createRefreshUserCompletable(): Completable {
    return refreshCurrentUser
        .refresh()
        .onErrorComplete()
  }

  @SuppressLint("CheckResult")
  private fun runRefreshUserTask(refreshUser: Completable) {
    // The refresh call should not get canceled when the screen is closed
    // (i.e., this chain gets disposed). So it's not a part of this Rx chain.
    refreshUser
        .subscribeOn(schedulers.io())
        .subscribe {
          // TODO (vs) 26/05/20: Move triggering this to the `Update` class later
          approvalStatusUpdatedAtPref.set(Instant.now(utcClock))
        }
  }

  private fun loadUser(): ObservableTransformer<LoadUser, PatientsTabEvent> {
    return ObservableTransformer { effects ->
      effects
          .switchMap { userSession.loggedInUser() }
          .filterAndUnwrapJust()
          .map(::UserDetailsLoaded)
    }
  }

  private fun loadRequiredInfoForShowingApprovalStatus(): ObservableTransformer<LoadInfoForShowingApprovalStatus, PatientsTabEvent> {
    return ObservableTransformer { effects ->
      effects
          .observeOn(schedulers.io())
          .map {
            DataForShowingApprovedStatusLoaded(
                currentTime = Instant.now(utcClock),
                approvalStatusUpdatedAt = approvalStatusUpdatedAtPref.get(),
                hasBeenDismissed = hasUserDismissedApprovedStatusPref.get()
            )
          }
    }
  }

  private fun loadInfoForShowingAppUpdate(): ObservableTransformer<LoadInfoForShowingAppUpdateMessage, PatientsTabEvent> {
    return ObservableTransformer { effects ->
      effects
          .switchMap { checkAppUpdate.listen() }
          .map {
            val today = LocalDate.now(userClock)
            val updateLastShownOn = appUpdateDialogShownAtPref.get().toLocalDateAtZone(userClock.zone)

            RequiredInfoForShowingAppUpdateLoaded(
                isAppUpdateAvailable = it is AppUpdateState.ShowAppUpdate,
                appUpdateLastShownOn = updateLastShownOn,
                currentDate = today,
                appUpdateNudgePriority = (it as? AppUpdateState.ShowAppUpdate)?.appUpdateNudgePriority,
                appStaleness = (it as? AppUpdateState.ShowAppUpdate)?.appStaleness
            )
          }
    }
  }
}
