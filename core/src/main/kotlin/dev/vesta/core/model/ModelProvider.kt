package dev.vesta.core.model

import dev.vesta.core.ModelRole
import dev.vesta.core.config.ModelSpec

/**
 * 역할에 배정된 모델을 돌려준다. 필요하면 메모리 예산 안에서 다른 모델을 내린 뒤 쓸 수 있게 준비한다 (PROJECT.md 2.3, M-03).
 */
interface ModelProvider {
    suspend fun acquire(role: ModelRole): ModelSpec
}

class ModelNotConfiguredException(role: ModelRole) :
    IllegalStateException("모델 역할 ${role.name.lowercase()}이(가) 설정되지 않았습니다. ~/.vesta/config.yaml의 models.${role.name.lowercase()}를 설정하세요.")
