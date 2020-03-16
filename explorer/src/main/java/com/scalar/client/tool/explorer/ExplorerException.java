/*
 * This file is part of the Scalar DL Explorer.
 * Copyright (c) 2019 Scalar, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license.  For more information, please contact Scalar, Inc.
 */

package com.scalar.client.tool.explorer;

import com.scalar.dl.ledger.service.StatusCode;

public class ExplorerException extends RuntimeException {
  private final StatusCode code;

  public ExplorerException(String message, StatusCode code) {
    super(message);
    this.code = code;
  }

  public ExplorerException(String message, Throwable cause, StatusCode code) {
    super(message, cause);
    this.code = code;
  }

  public StatusCode getStatusCode() {
    return this.code;
  }
}
