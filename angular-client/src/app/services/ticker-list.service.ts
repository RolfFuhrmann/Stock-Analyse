import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  TickerList, TickerListDetail,
  TickerListRequest, TickerSymbol, TickerSymbolRequest,
} from '../models/stock.models';

@Injectable({ providedIn: 'root' })
export class TickerListService {
  private readonly http    = inject(HttpClient);
  private readonly baseUrl = 'http://localhost:8013/api/lists';

  getLists(): Observable<TickerList[]> {
    return this.http.get<TickerList[]>(this.baseUrl);
  }

  getListDetail(id: number): Observable<TickerListDetail> {
    return this.http.get<TickerListDetail>(`${this.baseUrl}/${id}`);
  }

  createList(request: TickerListRequest): Observable<TickerList> {
    return this.http.post<TickerList>(this.baseUrl, request);
  }

  updateList(id: number, request: TickerListRequest): Observable<TickerList> {
    return this.http.put<TickerList>(`${this.baseUrl}/${id}`, request);
  }

  deleteList(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  addSymbol(listId: number, request: TickerSymbolRequest): Observable<TickerSymbol> {
    return this.http.post<TickerSymbol>(`${this.baseUrl}/${listId}/symbols`, request);
  }

  updateSymbol(listId: number, symbolId: number, request: TickerSymbolRequest): Observable<TickerSymbol> {
    return this.http.put<TickerSymbol>(`${this.baseUrl}/${listId}/symbols/${symbolId}`, request);
  }

  deleteSymbol(listId: number, symbolId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${listId}/symbols/${symbolId}`);
  }
}
