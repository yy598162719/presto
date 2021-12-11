/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution.scheduler;

import java.util.Collection;

/**
 * 多个stage谁先生成task的问题，在Presto中由ExecutionPolicy接口决定,这里有两个实现
 * 具体使用哪种顺序是由execution_policy的配置决定的
 * 默认是AllAtOnceExecutionPolicy会按照Stage执行的上下游关系依次调度Stage
 */
public interface ExecutionPolicy
{
    ExecutionSchedule createExecutionSchedule(Collection<StageExecutionAndScheduler> stages);
}
