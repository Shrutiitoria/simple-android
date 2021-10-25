package org.simple.clinic.selectcountry

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import com.google.android.material.composethemeadapter.MdcTheme
import com.jakewharton.rxbinding3.view.clicks
import com.spotify.mobius.functions.Consumer
import io.reactivex.Observable
import io.reactivex.rxkotlin.cast
import io.reactivex.subjects.PublishSubject
import kotlinx.parcelize.Parcelize
import org.simple.clinic.R
import org.simple.clinic.ReportAnalyticsEvents
import org.simple.clinic.appconfig.AppConfigRepository
import org.simple.clinic.appconfig.Country
import org.simple.clinic.appconfig.displayname.CountryDisplayNameFetcher
import org.simple.clinic.databinding.ScreenSelectcountryBinding
import org.simple.clinic.di.injector
import org.simple.clinic.navigation.v2.Router
import org.simple.clinic.navigation.v2.ScreenKey
import org.simple.clinic.navigation.v2.fragments.BaseScreen
import org.simple.clinic.selectstate.SelectStateScreen
import org.simple.clinic.util.scheduler.SchedulersProvider
import org.simple.clinic.util.unsafeLazy
import org.simple.clinic.widgets.indexOfChildId
import javax.inject.Inject

class SelectCountryScreen : BaseScreen<
    SelectCountryScreen.Key,
    ScreenSelectcountryBinding,
    SelectCountryModel,
    SelectCountryEvent,
    SelectCountryEffect,
    SelectCountryViewEffect>(), SelectCountryUi, UiActions {

  @Inject
  lateinit var appConfigRepository: AppConfigRepository

  @Inject
  lateinit var schedulersProvider: SchedulersProvider

  @Inject
  lateinit var countryDisplayNameFetcher: CountryDisplayNameFetcher

  @Inject
  lateinit var activity: AppCompatActivity

  @Inject
  lateinit var router: Router

  @Inject
  lateinit var effectHandlerFactory: SelectCountryEffectHandler.Factory

  private val countrySelectionViewFlipper
    get() = binding.countrySelectionViewFlipper

  private val countryListContainer
    get() = binding.countryListContainer

  private val tryAgain
    get() = binding.tryAgain

  private val errorMessageTextView
    get() = binding.errorMessageTextView

  private val hotEvents = PublishSubject.create<SelectCountryEvent>()

  private val progressBarViewIndex: Int by unsafeLazy {
    countrySelectionViewFlipper.indexOfChildId(R.id.progressBar)
  }

  private val countryListViewIndex: Int by unsafeLazy {
    countrySelectionViewFlipper.indexOfChildId(R.id.countryListContainer)
  }

  private val errorViewIndex: Int by unsafeLazy {
    countrySelectionViewFlipper.indexOfChildId(R.id.errorContainer)
  }

  override fun bindView(layoutInflater: LayoutInflater, container: ViewGroup?) = ScreenSelectcountryBinding.inflate(layoutInflater,
      container,
      false)

  override fun defaultModel() = SelectCountryModel.FETCHING

  override fun events() = Observable
      .merge(
          retryClicks(),
          hotEvents
      )
      .compose(ReportAnalyticsEvents())
      .cast<SelectCountryEvent>()

  override fun createInit() = SelectCountryInit()

  override fun createUpdate() = SelectCountryUpdate()

  override fun createEffectHandler(viewEffectsConsumer: Consumer<SelectCountryViewEffect>) = effectHandlerFactory
      .create(viewEffectsConsumer)
      .build()

  override fun uiRenderer() = SelectCountryUiRenderer(this)

  override fun viewEffectHandler() = SelectCountryViewEffectHandler(this)

  override fun onAttach(context: Context) {
    super.onAttach(context)
    context.injector<SelectCountryScreenInjector>().inject(this)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    setupCountriesListContainer()
  }

  private fun setupCountriesListContainer() {
    countryListContainer.setContent {
      MdcTheme {
        val model by modelUpdatesAsState()

        CountriesListContainer(
            countries = model.countries.orEmpty(),
            chosenCountry = model.selectedCountry,
            onCountrySelected = { country ->
              hotEvents.onNext(CountryChosen(country))
            }
        )
      }
    }
  }

  private fun retryClicks(): Observable<RetryClicked> {
    return tryAgain
        .clicks()
        .map { RetryClicked }
  }

  override fun showProgress() {
    countrySelectionViewFlipper.displayedChild = progressBarViewIndex
  }

  override fun displaySupportedCountries(countries: List<Country>, chosenCountry: Country?) {
    countrySelectionViewFlipper.displayedChild = countryListViewIndex
  }

  override fun displayNetworkErrorMessage() {
    errorMessageTextView.setText(R.string.selectcountry_networkerror)
    countrySelectionViewFlipper.displayedChild = errorViewIndex
  }

  override fun displayServerErrorMessage() {
    errorMessageTextView.setText(R.string.selectcountry_servererror)
    countrySelectionViewFlipper.displayedChild = errorViewIndex
  }

  override fun displayGenericErrorMessage() {
    errorMessageTextView.setText(R.string.selectcountry_genericerror)
    countrySelectionViewFlipper.displayedChild = errorViewIndex
  }

  override fun goToStateSelectionScreen() {
    router.push(SelectStateScreen.Key())
  }

  @Parcelize
  data class Key(
      override val analyticsName: String = "Select Country"
  ) : ScreenKey() {

    override fun instantiateFragment() = SelectCountryScreen()
  }
}
