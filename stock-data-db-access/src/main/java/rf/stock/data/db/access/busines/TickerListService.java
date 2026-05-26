package rf.stock.data.db.access.busines;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import rf.stock.data.db.access.busines.TickerDtos.TickerListDetailResponse;
import rf.stock.data.db.access.busines.TickerDtos.TickerListRequest;
import rf.stock.data.db.access.busines.TickerDtos.TickerListResponse;
import rf.stock.data.db.access.busines.TickerDtos.TickerSymbolRequest;
import rf.stock.data.db.access.busines.TickerDtos.TickerSymbolResponse;
import rf.stock.data.db.access.busines.TickerDtos.YahooSymbolsResponse;
import rf.stock.data.db.access.exception.DuplicateResourceException;
import rf.stock.data.db.access.exception.ResourceNotFoundException;
import rf.stock.data.db.access.model.TickerList;
import rf.stock.data.db.access.model.TickerSymbol;
import rf.stock.data.db.access.repository.TickerListRepository;
import rf.stock.data.db.access.repository.TickerSymbolRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TickerListService {

    private final TickerListRepository listRepository;
    private final TickerSymbolRepository symbolRepository;

    // ── Listen ────────────────────────────────────────────────────────────────

    public List<TickerListResponse> getAllLists() {
        return listRepository.findAll().stream()
                .map(this::toListResponse)
                .toList();
    }

    public TickerListDetailResponse getListById(Long id) {
        TickerList list = findListById(id);
        return toListDetailResponse(list);
    }

    public TickerListDetailResponse getListByCode(String code) {
        TickerList list = listRepository.findByCode(code)
                .orElseThrow(() -> ResourceNotFoundException.forListCode(code));
        return toListDetailResponse(list);
    }

    @Transactional
    public TickerListResponse createList(TickerListRequest request) {
        if (listRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException(
                    "Ticker-Liste mit Code '" + request.code() + "' existiert bereits");
        }
        TickerList list = TickerList.builder()
                .name(request.name())
                .code(request.code().toUpperCase())
                .description(request.description())
                .build();
        return toListResponse(listRepository.save(list));
    }

    @Transactional
    public TickerListResponse updateList(Long id, TickerListRequest request) {
        TickerList list = findListById(id);

        // Code-Änderung nur erlauben wenn der neue Code noch nicht vergeben ist
        if (!list.getCode().equals(request.code()) && listRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException(
                    "Ticker-Liste mit Code '" + request.code() + "' existiert bereits");
        }
        list.setName(request.name());
        list.setCode(request.code().toUpperCase());
        list.setDescription(request.description());
        return toListResponse(listRepository.save(list));
    }

    @Transactional
    public void deleteList(Long id) {
        if (!listRepository.existsById(id)) {
            throw ResourceNotFoundException.forList(id);
        }
        // Symbole werden via ON DELETE CASCADE in der DB mitgelöscht
        listRepository.deleteById(id);
    }

    // ── Symbole ───────────────────────────────────────────────────────────────

    public List<TickerSymbolResponse> getSymbolsByListId(Long listId) {
        findListById(listId); // Prüft ob Liste existiert
        return symbolRepository.findByTickerListId(listId).stream()
                .map(this::toSymbolResponse)
                .toList();
    }

    /**
     * Liefert die yahoo_symbols einer Liste – direkt verwendbar für den
     * agent-service.
     */
    public YahooSymbolsResponse getYahooSymbols(String listCode) {
        TickerList list = listRepository.findByCode(listCode)
                .orElseThrow(() -> ResourceNotFoundException.forListCode(listCode));
        List<String> yahooSymbols = symbolRepository.findYahooSymbolsByListId(list.getId());
        return new YahooSymbolsResponse(list.getCode(), list.getName(), yahooSymbols);
    }

    @Transactional
    public TickerSymbolResponse addSymbol(Long listId, TickerSymbolRequest request) {
        TickerList list = findListById(listId);

        if (symbolRepository.existsByTickerListIdAndRawSymbol(listId, request.rawSymbol().toUpperCase())) {
            throw new DuplicateResourceException(
                    "Symbol '" + request.rawSymbol() + "' existiert bereits in Liste " + listId);
        }
        TickerSymbol symbol = TickerSymbol.builder()
                .tickerList(list)
                .rawSymbol(request.rawSymbol().toUpperCase())
                .yahooSymbol(request.exchange().normalizeForYahoo(request.rawSymbol()))
                .exchange(request.exchange())
                .displayName(request.displayName())
                .build();
        return toSymbolResponse(symbolRepository.save(symbol));
    }

    @Transactional
    public TickerSymbolResponse updateSymbol(Long listId, Long symbolId, TickerSymbolRequest request) {
        findListById(listId);
        TickerSymbol symbol = symbolRepository.findById(symbolId)
                .orElseThrow(() -> ResourceNotFoundException.forSymbol(symbolId));

        // Sicherstellen dass das Symbol zur angegebenen Liste gehört
        if (!symbol.getTickerList().getId().equals(listId)) {
            throw ResourceNotFoundException.forSymbol(symbolId);
        }
        symbol.setRawSymbol(request.rawSymbol().toUpperCase());
        symbol.setYahooSymbol(request.exchange().normalizeForYahoo(request.rawSymbol()));
        symbol.setExchange(request.exchange());
        symbol.setDisplayName(request.displayName());
        return toSymbolResponse(symbolRepository.save(symbol));
    }

    @Transactional
    public void deleteSymbol(Long listId, Long symbolId) {
        findListById(listId);
        TickerSymbol symbol = symbolRepository.findById(symbolId)
                .orElseThrow(() -> ResourceNotFoundException.forSymbol(symbolId));
        if (!symbol.getTickerList().getId().equals(listId)) {
            throw ResourceNotFoundException.forSymbol(symbolId);
        }
        symbolRepository.delete(symbol);
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private TickerList findListById(Long id) {
        return listRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forList(id));
    }

    private TickerListResponse toListResponse(TickerList list) {
        return new TickerListResponse(
                list.getId(),
                list.getName(),
                list.getCode(),
                list.getDescription(),
                list.getSymbols().size(),
                list.getCreatedAt(),
                list.getUpdatedAt());
    }

    private TickerListDetailResponse toListDetailResponse(TickerList list) {
        List<TickerSymbolResponse> symbols = list.getSymbols().stream()
                .map(this::toSymbolResponse)
                .toList();
        return new TickerListDetailResponse(
                list.getId(),
                list.getName(),
                list.getCode(),
                list.getDescription(),
                symbols,
                list.getCreatedAt(),
                list.getUpdatedAt());
    }

    private TickerSymbolResponse toSymbolResponse(TickerSymbol symbol) {
        return new TickerSymbolResponse(
                symbol.getId(),
                symbol.getRawSymbol(),
                symbol.getYahooSymbol(),
                symbol.getExchange(),
                symbol.getDisplayName(),
                symbol.getCreatedAt(),
                symbol.getUpdatedAt());
    }
}
