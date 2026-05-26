package rf.stock.data.db.access;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rf.stock.data.db.access.busines.Exchange;
import rf.stock.data.db.access.busines.TickerDtos.*;
import rf.stock.data.db.access.busines.TickerListService;
import rf.stock.data.db.access.exception.DuplicateResourceException;
import rf.stock.data.db.access.exception.ResourceNotFoundException;
import rf.stock.data.db.access.model.TickerList;
import rf.stock.data.db.access.model.TickerSymbol;
import rf.stock.data.db.access.repository.TickerListRepository;
import rf.stock.data.db.access.repository.TickerSymbolRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TickerListServiceTest {

    @Mock
    private TickerListRepository listRepository;

    @Mock
    private TickerSymbolRepository symbolRepository;

    @InjectMocks
    private TickerListService service;

    private TickerList sampleList;

    @BeforeEach
    void setUp() {
        sampleList = TickerList.builder()
            .id(1L)
            .name("DAX 40")
            .code("DAX40")
            .description("Deutscher Aktienindex")
            .symbols(new ArrayList<>())
            .build();
        sampleList.setCreatedAt(LocalDateTime.now());
        sampleList.setUpdatedAt(LocalDateTime.now());
    }

    // ── Exchange Normalisierung ───────────────────────────────────────────────

    @Nested
    @DisplayName("Exchange.normalizeForYahoo")
    class ExchangeNormalizationTest {

        @Test
        @DisplayName("XETRA fügt .DE-Suffix hinzu")
        void xetraAddsDeSuffix() {
            assertThat(Exchange.XETRA.normalizeForYahoo("ADS")).isEqualTo("ADS.DE");
        }

        @Test
        @DisplayName("NYSE lässt Ticker unverändert")
        void nyseNoSuffix() {
            assertThat(Exchange.NYSE.normalizeForYahoo("AAPL")).isEqualTo("AAPL");
        }

        @Test
        @DisplayName("NASDAQ lässt Ticker unverändert")
        void nasdaqNoSuffix() {
            assertThat(Exchange.NASDAQ.normalizeForYahoo("MSFT")).isEqualTo("MSFT");
        }

        @Test
        @DisplayName("LSE fügt .L-Suffix hinzu")
        void lseAddsLSuffix() {
            assertThat(Exchange.LSE.normalizeForYahoo("HSBA")).isEqualTo("HSBA.L");
        }

        @Test
        @DisplayName("Bereits normalisierter Ticker wird nicht doppelt gesuffixed")
        void alreadyNormalizedNotDoubled() {
            assertThat(Exchange.XETRA.normalizeForYahoo("ADS.DE")).isEqualTo("ADS.DE");
        }

        @Test
        @DisplayName("Normalisierung konvertiert zu Großbuchstaben")
        void normalizesToUpperCase() {
            assertThat(Exchange.NYSE.normalizeForYahoo("aapl")).isEqualTo("AAPL");
        }

        @Test
        @DisplayName("Leerer rawSymbol wirft IllegalArgumentException")
        void emptySymbolThrows() {
            assertThatThrownBy(() -> Exchange.XETRA.normalizeForYahoo(""))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── Listen-CRUD ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Listen-Verwaltung")
    class ListCrudTest {

        @Test
        @DisplayName("getAllLists gibt alle Listen zurück")
        void getAllListsReturnsList() {
            when(listRepository.findAll()).thenReturn(List.of(sampleList));
            List<TickerListResponse> result = service.getAllLists();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).code()).isEqualTo("DAX40");
        }

        @Test
        @DisplayName("getListById wirft ResourceNotFoundException bei unbekannter ID")
        void getListByIdThrowsWhenNotFound() {
            when(listRepository.findById(99L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getListById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("createList legt neue Liste an")
        void createListSuccess() {
            TickerListRequest request = new TickerListRequest("Test", "TEST1", "Beschreibung");
            when(listRepository.existsByCode("TEST1")).thenReturn(false);
            when(listRepository.save(any())).thenReturn(sampleList);

            TickerListResponse response = service.createList(request);
            assertThat(response).isNotNull();
            verify(listRepository).save(any(TickerList.class));
        }

        @Test
        @DisplayName("createList wirft DuplicateResourceException bei doppeltem Code")
        void createListDuplicateCodeThrows() {
            TickerListRequest request = new TickerListRequest("DAX 40", "DAX40", null);
            when(listRepository.existsByCode("DAX40")).thenReturn(true);

            assertThatThrownBy(() -> service.createList(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("DAX40");
        }

        @Test
        @DisplayName("deleteList wirft ResourceNotFoundException bei unbekannter ID")
        void deleteListNotFoundThrows() {
            when(listRepository.existsById(99L)).thenReturn(false);
            assertThatThrownBy(() -> service.deleteList(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── Symbol-CRUD ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Symbol-Verwaltung")
    class SymbolCrudTest {

        @Test
        @DisplayName("addSymbol berechnet yahoo_symbol automatisch")
        void addSymbolCalculatesYahooSymbol() {
            TickerSymbolRequest request = new TickerSymbolRequest("BMW", Exchange.XETRA, "BMW AG");
            when(listRepository.findById(1L)).thenReturn(Optional.of(sampleList));
            when(symbolRepository.existsByTickerListIdAndRawSymbol(1L, "BMW")).thenReturn(false);

            TickerSymbol savedSymbol = TickerSymbol.builder()
                .id(10L)
                .tickerList(sampleList)
                .rawSymbol("BMW")
                .yahooSymbol("BMW.DE")
                .exchange(Exchange.XETRA)
                .displayName("BMW AG")
                .build();
            savedSymbol.setCreatedAt(LocalDateTime.now());
            savedSymbol.setUpdatedAt(LocalDateTime.now());

            when(symbolRepository.save(any())).thenReturn(savedSymbol);

            TickerSymbolResponse response = service.addSymbol(1L, request);
            assertThat(response.yahooSymbol()).isEqualTo("BMW.DE");
            assertThat(response.rawSymbol()).isEqualTo("BMW");
        }

        @Test
        @DisplayName("addSymbol wirft DuplicateResourceException bei doppeltem Symbol")
        void addSymbolDuplicateThrows() {
            TickerSymbolRequest request = new TickerSymbolRequest("ADS", Exchange.XETRA, "Adidas");
            when(listRepository.findById(1L)).thenReturn(Optional.of(sampleList));
            when(symbolRepository.existsByTickerListIdAndRawSymbol(1L, "ADS")).thenReturn(true);

            assertThatThrownBy(() -> service.addSymbol(1L, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("ADS");
        }
    }
}
