/*
 * Saldo - http://github.com/kria/saldo
 * 
 * Copyright (C) 2010 Kristian Adrup
 * 
 * This file is part of Saldo.
 * 
 * Saldo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Saldo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.adrup.saldo.bank.ica;

import com.adrup.http.EasySSLSocketFactory;
import com.adrup.http.HttpException;
import com.adrup.http.HttpHelper;
import com.adrup.saldo.SaldoHttpClient;
import com.adrup.saldo.bank.Account;
import com.adrup.saldo.bank.AccountHashKey;
import com.adrup.saldo.bank.AuthenticationException;
import com.adrup.saldo.bank.BankException;
import com.adrup.saldo.bank.BankLogin;
import com.adrup.saldo.bank.BankManager;
import com.adrup.saldo.bank.RemoteAccount;

import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;

import android.content.Context;
import android.text.Html;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A ICA implementation of {@link BankManager}.
 * 
 * @author Kristian Adrup
 * 
 */
public class IcaManager implements BankManager {
	private static final String TAG = "IcaManager";
	private static final String NAME = "ICA";
	private static final String LOGIN_URL = "https://www.ica.se/Logga-in/?returnUrl=/Mina-sidor/Konto--Saldo/";
	private static final String USER_PARAM = "ctl00$cphFullWidthContainer$ctl02$tbPersno";
	private static final String PASS_PARAM = "ctl00$cphFullWidthContainer$ctl02$tbPasswd";
	private static final String BUTTON_PARAM = "ctl00$cphFullWidthContainer$ctl02$btnLogin";
	private static final String VIEWSTATE_PARAM = "__VIEWSTATE";

	private static final String VIEWSTATE_REGEX = "id=\"__VIEWSTATE\"\\s+value=\"([^\"]+)\"";
	private static final String ACCOUNTS_REGEX = "<tr>\\s*<th>\\s*([^<]+):\\s*</th>\\s*<td\\s+class=\"amount\">\\s*<span[^>]*>([\\d -\\.,]+) kr</span>";

	private BankLogin mBankLogin;
	private Context mContext;

	public IcaManager(BankLogin bankLogin, Context context) {
		this.mBankLogin = bankLogin;
		this.mContext = context;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public Map<AccountHashKey, RemoteAccount> getAccounts() throws BankException {
		return getAccounts(new LinkedHashMap<AccountHashKey, RemoteAccount>());
	}

	@Override
	public Map<AccountHashKey, RemoteAccount> getAccounts(Map<AccountHashKey, RemoteAccount> accounts) throws BankException {
		Log.d(TAG, "getAccounts()");

		SchemeRegistry schemeRegistry = new SchemeRegistry();
		schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
		// Android doesn't like ICA's cert, so we need a forgiving TrustManager
		schemeRegistry.register(new Scheme("https", new EasySSLSocketFactory(), 443));
		HttpParams params = new BasicHttpParams();
		HttpClient httpClient = new SaldoHttpClient(mContext, new ThreadSafeClientConnManager(params, schemeRegistry), null);

		try {
			// get login page

			Log.d(TAG, "getting login page");

			String res = HttpHelper.get(httpClient, LOGIN_URL);

			Matcher matcher = Pattern.compile(VIEWSTATE_REGEX).matcher(res);
			if (!matcher.find()) {
				Log.e(TAG, "No viewstate match.");
				Log.d(TAG, res);
				throw new IcaException("No viewState match.");
			}
			String viewState = matcher.group(1);
			Log.d(TAG, "viewState= " + viewState);

			// do login post, should redirect us to the accounts page
			List<NameValuePair> parameters = new ArrayList<NameValuePair>(3);

			parameters.add(new BasicNameValuePair(VIEWSTATE_PARAM, viewState));
			parameters.add(new BasicNameValuePair(USER_PARAM, mBankLogin.getUsername()));
			parameters.add(new BasicNameValuePair(PASS_PARAM, mBankLogin.getPassword()));
			parameters.add(new BasicNameValuePair(BUTTON_PARAM, "Logga in"));

			res = HttpHelper.post(httpClient, LOGIN_URL, parameters);

			if (res.contains("login-error")) {
				Log.d(TAG, "auth fail");
				throw new AuthenticationException("auth fail");
			}

			// extract account info
			matcher = Pattern.compile(ACCOUNTS_REGEX).matcher(res);

			int remoteId = 1;
			int count = 0;
			while (matcher.find()) {
				count++;
				int groupCount = matcher.groupCount();
				for (int i = 1; i <= groupCount; i++) {
					Log.d(TAG, i + ":" + matcher.group(i));
				}
				if (groupCount < 2) {
					throw new BankException("Pattern match issue: groupCount < 2");
				}

				int ordinal = remoteId;
				String name = Html.fromHtml(matcher.group(1)).toString();
				long balance = Long.parseLong(matcher.group(2).replaceAll("\\,|\\.| ", "")) / 100;
				accounts.put(new AccountHashKey(String.valueOf(remoteId), mBankLogin.getId()), new Account(String.valueOf(remoteId),
						mBankLogin.getId(), ordinal, name, balance));
				remoteId++;
			}
			if (count == 0) {
				Log.d(TAG, "no accounts added");
				Log.d(TAG, res);
			}
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
			throw new IcaException(e.getMessage(), e);
		} catch (HttpException e) {
			Log.e(TAG, e.getMessage(), e);
			throw new IcaException(e.getMessage(), e);
		} finally {
			httpClient.getConnectionManager().shutdown();
		}

		return accounts;
	}
}
