package rf.stock.data.db.access;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rf.stock.data.db.access.busines.TickerDtos.*;
import rf.stock.data.db.access.busines.TickerListService;
import rf.stock.data.db.access.exception.DuplicateResourceException;
import rf.stock.data.db.access.exception.ResourceNotFoundException;
import rf.stock.data.db.access.model.TickerList;
import rf.stock.data.db.access.model.TickerSymbol;
import rf.stock.data.db.access.repository.TickerListRepository;
import rf.stock.data.db.access.repository.TickerSymbolRepository;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TickerListServiceTest {

    @Mock private TickerListRepository listRepository;
    @Mock private TickerSymbolRepository symbolRepository;
    @InjectMocks private TickerListService service;

    private TickerList sampleList;

    @BeforeEach
    void setUp() {
        sampleList = TickerList.builder()
            .id(1L).name("DAX 40").code("DAX40")
            .description("Deutscher Aktienindex")
            .source("yahoo")
            .tickerFormat("XETRA")
            .customSuffix(null)
            .symbols(new ArrayList<>()).build();
        sampleList.setCreatedAt(LocalDateTime.now());
        sampleList.setUpdatedAt(LocalDateTime.now());
    }

    @Nested @DisplayName("Listen-Verwaltung")
    class ListCrudTest {

        @Test @DisplayName("getAllLists gibt alle Listen zurück")
        void getAllListsReturnsList() {
            when(listRepository.findAll()).thenReturn(List.of(sampleList));
            List<TickerListResponse> result = service.getAllLists();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).code()).isEqualTo("DAX40");
            assertThat(result.get(0).source()).isEqualTo("yahoo");
            assertThat(result.get(0).tickerFormat()).isEqualTo("XETRA");
        }

        @Test @DisplayName("createList legt neue Liste an")
        void createListSuccess() {
            // 6 Parameter: name, code, description, source, tickerFormat, customSuffix
            TickerListRequest req = new TickerListRequest("Test", "TEST1", null, "yahoo", "RAW", null);
            when(listRepository.existsByCode("TEST1")).thenReturn(false);
            when(listRepository.save(any())).thenReturn(sampleList);
            TickerListResponse resp = service.createList(req);
            assertThat(resp).isNotNull();
            verify(listRepository).save(any(TickerList.class));
        }

        @Test @DisplayName("createList wirft DuplicateResourceException bei doppeltem Code")
        void createListDuplicateThrows() {
            TickerListRequest req = new TickerListRequest("DAX 40", "DAX40", null, "yahoo", "XETRA", null);
            when(listRepository.existsByCode("DAX40")).thenReturn(true);
            assertThatThrownBy(() -> service.createList(req))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("DAX40");
        }

        @Test @DisplayName("getListById wirft ResourceNotFoundException bei unbekannter ID")
        void getListByIdNotFoundThrows() {
            when(listRepository.findById(99L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getListById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test @DisplayName("deleteList wirft ResourceNotFoundException bei unbekannter ID")
        void deleteListNotFoundThrows() {
            when(listRepository.existsById(99L)).thenReturn(false);
            assertThatThrownBy(() -> service.deleteList(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test @DisplayName("createList mit CUSTOM-Format und Suffix")
        void createListWithCustomFormat() {
            TickerListRequest req = new TickerListRequest(
                "LSE UK", "LSE_UK", "London Stock Exchange", "yahoo", "CUSTOM", ".L"
            );
            when(listRepository.existsByCode("LSE_UK")).thenReturn(false);
            when(listRepository.save(any())).thenReturn(sampleList);
            TickerListResponse resp = service.createList(req);
            assertThat(resp).isNotNull();
            verify(listRepository).save(any(TickerList.class));
        }
    }

    @Nested @DisplayName("Symbol-Verwaltung")
    class SymbolCrudTest {

        @Test @DisplayName("addSymbol speichert rawSymbol ohne yahoo-Suffix")
        void addSymbolStoresRawSymbol() {
            TickerSymbolRequest req = new TickerSymbolRequest("BMW", "BMW AG");
            when(listRepository.findById(1L)).thenReturn(Optional.of(sampleList));
            when(symbolRepository.existsByTickerListIdAndRawSymbol(1L, "BMW")).thenReturn(false);

            TickerSymbol saved = TickerSymbol.builder()
                .id(10L).tickerList(sampleList)
                .rawSymbol("BMW").displayName("BMW AG").build();
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());

            when(symbolRepository.save(any())).thenReturn(saved);

            TickerSymbolResponse resp = service.addSymbol(1L, req);
            // Normalisierung (.DE) findet im Angular-Client statt, nicht im Backend
            assertThat(resp.rawSymbol()).isEqualTo("BMW");
        }

        @Test @DisplayName("addSymbol wirft DuplicateResourceException bei doppeltem Symbol")
        void addSymbolDuplicateThrows() {
            TickerSymbolRequest req = new TickerSymbolRequest("ADS", "Adidas");
            when(listRepository.findById(1L)).thenReturn(Optional.of(sampleList));
            when(symbolRepository.existsByTickerListIdAndRawSymbol(1L, "ADS")).thenReturn(true);
            assertThatThrownBy(() -> service.addSymbol(1L, req))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("ADS");
        }
    }
}
