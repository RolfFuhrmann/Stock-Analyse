package rf.stock.data.db.access.busines;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rf.stock.data.db.access.busines.TickerDtos.*;
import rf.stock.data.db.access.exception.DuplicateResourceException;
import rf.stock.data.db.access.exception.ResourceNotFoundException;
import rf.stock.data.db.access.model.TickerList;
import rf.stock.data.db.access.model.TickerSymbol;
import rf.stock.data.db.access.repository.TickerListRepository;
import rf.stock.data.db.access.repository.TickerSymbolRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TickerListService {

    private final TickerListRepository  listRepository;
    private final TickerSymbolRepository symbolRepository;

    // ── Listen ────────────────────────────────────────────────────────────────

    public List<TickerListResponse> getAllLists() {
        return listRepository.findAll().stream().map(this::toListResponse).toList();
    }

    public TickerListDetailResponse getListById(Long id) {
        return toListDetailResponse(findListById(id));
    }

    public TickerListDetailResponse getListByCode(String code) {
        return toListDetailResponse(
            listRepository.findByCode(code)
                .orElseThrow(() -> ResourceNotFoundException.forListCode(code))
        );
    }

    public RawSymbolsResponse getRawSymbols(String listCode) {
        TickerList list = listRepository.findByCode(listCode)
            .orElseThrow(() -> ResourceNotFoundException.forListCode(listCode));
        List<String> rawSymbols = symbolRepository.findRawSymbolsByListId(list.getId());
        return new RawSymbolsResponse(
            list.getCode(), list.getName(), list.getSource(),
            list.getTickerFormat(), list.getCustomSuffix(),
            rawSymbols
        );
    }

    @Transactional
    public TickerListResponse createList(TickerListRequest request) {
        if (listRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException(
                "Ticker-Liste mit Code '" + request.code() + "' existiert bereits"
            );
        }
        TickerList list = TickerList.builder()
            .name(request.name())
            .code(request.code().toUpperCase())
            .description(request.description())
            .source(request.source())
            .tickerFormat(request.tickerFormat())
            .customSuffix(request.customSuffix())
            .build();
        return toListResponse(listRepository.save(list));
    }

    @Transactional
    public TickerListResponse updateList(Long id, TickerListRequest request) {
        TickerList list = findListById(id);
        if (!list.getCode().equals(request.code()) && listRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException(
                "Ticker-Liste mit Code '" + request.code() + "' existiert bereits"
            );
        }
        list.setName(request.name());
        list.setCode(request.code().toUpperCase());
        list.setDescription(request.description());
        list.setSource(request.source());
        list.setTickerFormat(request.tickerFormat());
        list.setCustomSuffix(request.customSuffix());
        return toListResponse(listRepository.save(list));
    }

    @Transactional
    public void deleteList(Long id) {
        if (!listRepository.existsById(id)) throw ResourceNotFoundException.forList(id);
        listRepository.deleteById(id);
    }

    // ── Symbole ───────────────────────────────────────────────────────────────

    public List<TickerSymbolResponse> getSymbolsByListId(Long listId) {
        findListById(listId);
        return symbolRepository.findByTickerListId(listId).stream()
            .map(this::toSymbolResponse).toList();
    }

    @Transactional
    public TickerSymbolResponse addSymbol(Long listId, TickerSymbolRequest request) {
        TickerList list = findListById(listId);
        String upper = request.rawSymbol().toUpperCase();
        if (symbolRepository.existsByTickerListIdAndRawSymbol(listId, upper)) {
            throw new DuplicateResourceException(
                "Symbol '" + upper + "' existiert bereits in Liste " + listId
            );
        }
        TickerSymbol symbol = TickerSymbol.builder()
            .tickerList(list)
            .rawSymbol(upper)
            .displayName(request.displayName())
            .build();
        return toSymbolResponse(symbolRepository.save(symbol));
    }

    @Transactional
    public TickerSymbolResponse updateSymbol(Long listId, Long symbolId, TickerSymbolRequest request) {
        findListById(listId);
        TickerSymbol symbol = symbolRepository.findById(symbolId)
            .orElseThrow(() -> ResourceNotFoundException.forSymbol(symbolId));
        if (!symbol.getTickerList().getId().equals(listId))
            throw ResourceNotFoundException.forSymbol(symbolId);
        symbol.setRawSymbol(request.rawSymbol().toUpperCase());
        symbol.setDisplayName(request.displayName());
        return toSymbolResponse(symbolRepository.save(symbol));
    }

    @Transactional
    public void deleteSymbol(Long listId, Long symbolId) {
        findListById(listId);
        TickerSymbol symbol = symbolRepository.findById(symbolId)
            .orElseThrow(() -> ResourceNotFoundException.forSymbol(symbolId));
        if (!symbol.getTickerList().getId().equals(listId))
            throw ResourceNotFoundException.forSymbol(symbolId);
        symbolRepository.delete(symbol);
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private TickerList findListById(Long id) {
        return listRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.forList(id));
    }

    private TickerListResponse toListResponse(TickerList l) {
        return new TickerListResponse(
            l.getId(), l.getName(), l.getCode(), l.getDescription(),
            l.getSource(), l.getTickerFormat(), l.getCustomSuffix(),
            l.getSymbols().size(), l.getCreatedAt(), l.getUpdatedAt()
        );
    }

    private TickerListDetailResponse toListDetailResponse(TickerList l) {
        return new TickerListDetailResponse(
            l.getId(), l.getName(), l.getCode(), l.getDescription(),
            l.getSource(), l.getTickerFormat(), l.getCustomSuffix(),
            l.getSymbols().stream().map(this::toSymbolResponse).toList(),
            l.getCreatedAt(), l.getUpdatedAt()
        );
    }

    private TickerSymbolResponse toSymbolResponse(TickerSymbol s) {
        return new TickerSymbolResponse(
            s.getId(), s.getRawSymbol(), s.getDisplayName(),
            s.getCreatedAt(), s.getUpdatedAt()
        );
    }
}
