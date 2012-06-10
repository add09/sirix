/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the
 * names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.axis;

import org.sirix.api.IAxis;
import org.sirix.api.IFilter;

/**
 * <h1>TestAxis</h1>
 * 
 * <p>
 * Perform a test on a given axis.
 * </p>
 */
public class FilterAxis extends AbsAxis {

  /** Axis to test. */
  private final IAxis mAxis;

  /** Test to apply to axis. */
  private final IFilter[] mAxisFilter;

  /**
   * Constructor initializing internal state.
   * 
   * @param paramAxis
   *          axis to iterate over
   * @param paramAxisTest
   *          test to perform for each node found with axis
   */
  public FilterAxis(final IAxis paramAxis, final IFilter... paramAxisTest) {
    super(paramAxis.getTransaction());
    mAxis = paramAxis;
    mAxisFilter = paramAxisTest;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void reset(final long paramNodeKey) {
    super.reset(paramNodeKey);
    if (mAxis != null) {
      mAxis.reset(paramNodeKey);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean hasNext() {
    if (isNext()) {
      return true;
    }
    resetToLastKey();
    while (mAxis.hasNext()) {
      mKey = mAxis.next();
      boolean filterResult = true;
      for (final IFilter filter : mAxisFilter) {
        filterResult = filterResult && filter.filter();
      }
      if (filterResult) {
        return true;
      }
    }
    resetToStartKey();
    return false;
  }

  /**
   * Returns the inner axis.
   * 
   * @return the axis
   */
  public final IAxis getAxis() {
    return mAxis;
  }

}